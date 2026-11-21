package com.vrgames.fishing.content

import android.util.Log
import com.vrgames.fishing.core.Angler
import com.vrgames.fishing.core.HandReading
import com.vrgames.fishing.core.OverheadCast
import com.vrgames.fishing.core.SimHands
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.core.Vec3
import com.vrgames.fishing.game.Game
import com.vrgames.fishing.render.LakeView
import com.vrgames.fishing.render.Sfx
import kotlin.math.exp
import kotlin.math.sin

/**
 * v0.5: the stage's side of [Angler] (the pure hand-play logic in core). Each frame it turns the tracker's data into
 * [HandReading]s (palm and fingertip POSITIONS only, plus the tracker's timestamp), runs the angler, and shows what it
 * holds (the rod drawn from the cast's state, the net). The system's pinch and drag events come in through [pinch],
 * [dragStart], [dragMove], [dragEnd] (main thread). What the angler does goes to [Game] on the main thread.
 *
 * Debug builds: `hands <scenario>` swaps the tracker for [SimHands] (the emulator has no hand tracking); its readings
 * are fed in many small steps a frame (the emulator draws about a frame a second), through the same angler.
 */
class HandPlay(private val post: (() -> Unit) -> Unit) {
    private val out = object : Angler.Out {
        override fun log(s: String) { Log.i(TAG, s) }
        override fun rodTaken(how: String) = post { Game.rodTaken(how) }
        override fun cocked() = post { Game.cocked() }
        override fun cast(c: OverheadCast.Result.Cast) {
            whipT = 0.0
            val launch = 25.0 + 15.0 * ((c.distance - OverheadCast.MIN_D) / (OverheadCast.MAX_D - OverheadCast.MIN_D)).coerceIn(0.0, 1.0)
            post { Game.castBy(OverheadCast.powerOf(c.distance), c.yaw, launch) }
        }
        override fun castFailed(why: String) = post { Game.castFailed(why) }
        override fun rodBack(why: String) = post { Game.rodBack(why) }
        override fun strike(how: String) = post { Game.strikeBy(how) }
        override fun reel(turns: Double, by: String) {
            wound += turns
            val quarter = wound >= 0.25
            if (quarter) wound -= 0.25 * kotlin.math.floor(wound / 0.25)
            post { Game.reel(turns, by); if (quarter) Game.sound?.play(Sfx.REEL) }
        }
        override fun netTaken(how: String) = post { Game.inHand(net = true) }
        override fun netBack(why: String) = post { Game.inHand(net = false); Game.netBack(why) }
        override fun netScooped(how: String) = post { Game.net() }
        override fun pinchOther(what: String) = post { Game.pinchOther(what) }
    }

    val angler = Angler(out)
    private var clock = 0.0
    private var whipT = -1.0
    private var wound = 0.0
    private var sim: SimHands? = null
    private var debugT = 0.0
    private var lastSimRight: HandReading? = null
    private var lastSimLeft: HandReading? = null

    /** Where the test's right (or left) hand pinches now, for the `gaze` hook (null: no simulated hands). */
    fun simPinchPoint(left: Boolean): Vec3? = (if (left) lastSimLeft else lastSimRight)?.pinchPoint

    /**
     * One frame of [dt] s: the tracker's readings [right]/[left] (seat frame, null when not tracked) and its [stamp];
     * [scenario] the debug hands to use instead (null: the tracker).
     */
    fun frame(dt: Double, right: HandReading?, left: HandReading?, stamp: Long?, scenario: String?, v: LakeView, t: Trip?, playing: Boolean) {
        if (scenario != sim?.scenario) {
            sim = scenario?.let { SimHands(it).also { s -> s.log = { m -> Log.i(TAG, m) } } }
            Log.i(TAG, "simulated hands: ${scenario ?: "off"}")
        }
        val uptime = android.os.SystemClock.uptimeMillis() / 1000.0
        // Only while fishing (not on the cards or the result): no rod is taken from behind a card.
        if (t == null || !playing) { angler.pause(); show(dt, v, world(v, t)); return }
        val w = world(v, t)
        v.handsMode = Game.handsOn || sim != null
        val s = sim
        if (s != null) {
            // Many small steps (the emulator's frames are far apart): each a reading of the simulated hands.
            val n = kotlin.math.ceil(dt / SIM_STEP).toInt().coerceIn(1, 400)
            val step = dt / n
            repeat(n) {
                clock += step
                val f = s.next(step, w, angler, t)
                lastSimRight = f.right; lastSimLeft = f.left
                angler.frame(clock, f.right, f.left, f.stamp, uptime, w)
            }
        } else {
            clock += dt
            angler.frame(clock, right, left, stamp, uptime, w)
        }
        show(dt, v, w)
        debugT += dt
        if (debugT >= 0.25) { debugT = 0.0; val line = angler.debugLine(); val live = angler.handsLive; post { Game.debugHands = line; Game.handsLive = live } }
    }

    private fun world(v: LakeView, t: Trip?) = Angler.World(
        state = t?.state ?: Trip.State.LANDED,
        rodGrip = v.rodGripTarget(),
        netGrip = v.restNetGrip,
        fish = if (t != null) v.fishNow(t) else Vec3.ZERO,
        landings = v.landingCount,
        ice = t?.lake?.ice == true,
        casts = t?.casts ?: 0,
    )

    /** The rod and the net where the angler holds them; the glows near a hand; the cast's whip. */
    private fun show(dt: Double, v: LakeView, w: Angler.World) {
        val rp = angler.rodPose
        v.handGrip = rp?.first
        v.handDir = rp?.second
        val np = angler.netPose
        v.netHand = np?.first
        v.netHoop = np?.second
        v.crankSpin = angler.crankSpin
        val near = listOf(angler.right, angler.left).any { h -> h.fresh && h.palm?.let { it.distanceTo(w.rodGrip) < GLOW_R } == true }
        v.gripNear = near
        v.crankNear = angler.reelEngaged(angler.right) || angler.reelEngaged(angler.left)
        val after = if (whipT >= 0) { whipT += dt; if (whipT > 1.2) { whipT = -1.0; 0.0 } else 22 * exp(-4.5 * whipT) * sin(whipT * 26) } else 0.0
        v.whip = after
    }

    // ---- The system's events (main thread) ----

    fun pinch(target: String?, at: Vec3?) = synchronized(Game.lock) { angler.pinch(target, at) }
    fun dragStart(target: String?, t: Double = uptime()) = synchronized(Game.lock) { angler.dragStart(target, t) }
    fun addCrank(turns: Double) = synchronized(Game.lock) { angler.addCrank(turns) }
    fun dragMove(d: Vec3, t: Double = uptime()) = synchronized(Game.lock) { angler.dragMove(d, t) }
    fun dragEnd() = synchronized(Game.lock) { angler.dragEnd() }
    fun dropNet() = synchronized(Game.lock) { angler.dropNet() }

    private fun uptime() = android.os.SystemClock.uptimeMillis() / 1000.0

    companion object {
        private const val TAG = "FishHands"
        /** A fresh hand this near the rod's grip makes it glow brighter. */
        const val GLOW_R = 0.3
        /** The simulated hands' reading interval (s). */
        const val SIM_STEP = 1.0 / SimHands.RATE
    }
}
