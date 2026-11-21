package com.vrgames.fishing.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * The overhead cast in two steps, from the palm's POSITION only (v0.5; device test 2026-11-20 on 0.4.0: 「拿起来以后竿子
 * 跑到身后，自己往近处甩出去；往前甩，线的方向不对」).
 *
 * 0.4 followed a virtual rod tip 1.2 m out along the hand's orientation: a degree of tracking jitter there is metres a
 * second at the tip, so a rod held still cocked and threw itself; the rod pointed wherever the knuckles said (behind
 * the head); repeated tracking frames read as a stop and released early; and lost tracking mid-throw still cast.
 *
 * Now:
 * 1. cock — the palm at least [COCK_UP] above where the rod was taken (or above [Layout.COCK_Y], over the shoulder)
 *    and at least [COCK_BACK] back from it, held [HOLD_S]: a 「咔」 and the rod is drawn tilted back;
 * 2. throw — forward from the back-most point: released at [THROW_SPEED] with [THROW_TRAVEL] travelled, or after
 *    [SHORT_TRAVEL] when the speed falls below [STOP_SHARE] of its peak (a stopped flick). Slower than [THROW_SPEED]
 *    at its peak is no cast ("too slow"). The distance runs [MIN_D]..[MAX_D] with the peak speed [THROW_SPEED]..
 *    [FULL_SPEED]; the direction is straight ahead plus the palm's own sideways travel (±[MAX_YAW]).
 * No new reading for [LOST_THROW_S] in the middle of a throw cancels it (tracking lost is never a release).
 *
 * Feed new readings only ([sample]) and the time since the last one ([gap]). The same steps take a system drag
 * (no hand tracking: the virtual palm moved by the drag), with [gap] meaning "the drag stopped".
 */
class OverheadCast {
    enum class Phase { HELD, COCKED }

    sealed class Result {
        object Cocked : Result()
        data class Cast(val distance: Double, val yaw: Double, val peak: Double, val travel: Double) : Result()
        data class Fail(val why: String) : Result()
    }

    var phase = Phase.HELD; private set
    /** How far the rod is drawn back, 0..1 (held: toward the cock; cocked: 1 until the throw brings it forward). */
    var drawn = 0.0; private set
    /** The throw's progress 0..1 (cocked), for the rod coming forward. */
    var thrown = 0.0; private set
    /** The current throw's sideways aim (degrees, + right), for the rod's heading. */
    var aimYaw = 0.0; private set
    /** The palm's forward speed now (m/s), for the log. */
    var forward = 0.0; private set
    var peak = 0.0; private set

    private var ref = Vec3.ZERO
    private var cockY = 0.0
    private var backZ = 0.0
    private var condSince = Double.NaN
    private var condNow = false
    private var start = Vec3.ZERO
    private var lastT = Double.NEGATIVE_INFINITY
    private var lastP = Vec3.ZERO
    private val samples = ArrayList<Pair<Double, Vec3>>()

    /** The rod was taken with the palm at [pickup] (the reference for the cock). */
    fun begin(pickup: Vec3) {
        ref = pickup
        cockY = minOf(pickup.y + COCK_UP, Layout.COCK_Y)
        backZ = minOf(pickup.z, BACK_REF_MAX) + COCK_BACK
        toHeld()
    }

    private fun toHeld() {
        phase = Phase.HELD; drawn = 0.0; thrown = 0.0; aimYaw = 0.0; forward = 0.0; peak = 0.0
        condSince = Double.NaN; condNow = false; samples.clear()
    }

    /** True while a throw is under way (cocked and coming forward). */
    val throwing get() = phase == Phase.COCKED && (start.z - lastP.z >= 0.04 || forward >= 0.5)

    /** A new reading at [t]: the palm at [p]. */
    fun sample(t: Double, p: Vec3): Result? {
        if (t <= lastT) return null
        lastT = t; lastP = p
        samples += t to p
        while (samples.size > 2 && t - samples.first().first > 0.2) samples.removeAt(0)
        val v = HandTrack.velocityOf(samples, HandTrack.VEL_S)
        forward = if (v == null) 0.0 else -v.z
        when (phase) {
            Phase.HELD -> {
                val up = ((p.y - ref.y) / (cockY - ref.y).coerceAtLeast(0.05)).coerceIn(0.0, 1.0)
                val back = ((p.z - ref.z) / (backZ - ref.z).coerceAtLeast(0.05)).coerceIn(0.0, 1.0)
                drawn = 0.75 * up + 0.25 * back
                condNow = p.y >= cockY && p.z >= backZ
                if (!condNow) { condSince = Double.NaN; return null }
                if (condSince.isNaN()) condSince = t
                if (t - condSince >= HOLD_S) return cock(p)
            }
            Phase.COCKED -> {
                // The throw runs from the back-most point since the cock.
                if (p.z > start.z) { start = p; peak = 0.0 }
                val travel = start.z - p.z
                if (travel > 0.005 && forward > peak) peak = forward
                thrown = (travel / THROW_TRAVEL).coerceIn(0.0, 1.0)
                val dx = p.x - start.x
                aimYaw = yawOf(dx, travel)
                if (forward >= THROW_SPEED && travel >= THROW_TRAVEL) return release(p)
                if (travel >= SHORT_TRAVEL && forward < peak * STOP_SHARE) return if (peak >= THROW_SPEED) release(p) else fail("slow")
                // Lowered well below where it was taken without coming forward: put down, not thrown.
                if (p.y < ref.y - 0.08 && travel < SHORT_TRAVEL) { toHeld(); return null }
            }
        }
        return null
    }

    /**
     * Time passing with no new reading for [gapS] seconds. With hands ([drag] false): a throw under way is cancelled
     * after [LOST_THROW_S] (tracking lost), a still cocked rod after [LOST_COCKED_S]. With a system drag ([drag] true)
     * no event means the hand stopped: the cock's hold counts on, a throw under way ends there.
     */
    fun gap(gapS: Double, drag: Boolean): Result? {
        if (lastT == Double.NEGATIVE_INFINITY) return null
        when (phase) {
            Phase.HELD -> if (drag && condNow && !condSince.isNaN() && (lastT + gapS) - condSince >= HOLD_S) return cock(lastP)
            Phase.COCKED -> {
                if (drag) {
                    if (gapS > DRAG_STOP_S) {
                        val travel = start.z - lastP.z
                        forward = 0.0
                        if (travel >= SHORT_TRAVEL) return if (peak >= THROW_SPEED) release(lastP) else fail("slow")
                    }
                } else if (gapS > (if (throwing) LOST_THROW_S else LOST_COCKED_S)) return fail("lost")
            }
        }
        return null
    }

    private fun cock(p: Vec3): Result {
        phase = Phase.COCKED
        start = p
        peak = 0.0
        drawn = 1.0
        thrown = 0.0
        condSince = Double.NaN
        return Result.Cocked
    }

    private fun fail(why: String): Result { toHeld(); return Result.Fail(why) }

    private fun release(p: Vec3): Result {
        val travel = start.z - p.z
        val c = Result.Cast(distanceOf(peak), yawOf(p.x - start.x, travel), peak, travel)
        toHeld()
        return c
    }

    companion object {
        const val COCK_UP = 0.18
        const val COCK_BACK = 0.08
        /** The cock's "back" is measured from the pickup, or from this far out when the rod was taken nearer (a lap). */
        const val BACK_REF_MAX = -0.40
        const val HOLD_S = 0.1
        const val THROW_SPEED = 1.2
        const val THROW_TRAVEL = 0.25
        const val SHORT_TRAVEL = 0.12
        const val STOP_SHARE = 0.5
        const val FULL_SPEED = 3.5
        const val MIN_D = 4.0
        const val MAX_D = 16.0
        const val MAX_YAW = 35.0
        /** The inward drift of a straight overhand throw (degrees) does not turn the cast. */
        const val YAW_DEADZONE = 10.0
        const val LOST_THROW_S = 0.10
        const val LOST_COCKED_S = 1.0
        const val DRAG_STOP_S = 0.08

        fun distanceOf(peak: Double) = MIN_D + (MAX_D - MIN_D) * ((peak - THROW_SPEED) / (FULL_SPEED - THROW_SPEED)).coerceIn(0.0, 1.0)

        fun yawOf(dx: Double, forward: Double): Double {
            if (forward < 0.03) return 0.0
            val a = Math.toDegrees(atan2(dx, forward))
            return (sign(a) * max(0.0, abs(a) - YAW_DEADZONE)).coerceIn(-MAX_YAW, MAX_YAW)
        }

        /** Power 0..1 for [Trip.cast] (3 + 13·p metres). */
        fun powerOf(distance: Double) = ((distance - 3.0) / 13.0).coerceIn(0.0, 1.0)

        /** The float's time in the air for a cast of [distance] m at [launchDeg]: about 0.6–1.6 s. */
        fun flightSeconds(distance: Double, launchDeg: Double): Double {
            val th = Math.toRadians(launchDeg.coerceIn(5.0, 75.0))
            val v = kotlin.math.sqrt(9.8 * distance / kotlin.math.max(sin(2 * th), 0.1))
            return (2 * v * sin(th) / 9.8).coerceIn(0.6, 1.6)
        }
    }
}
