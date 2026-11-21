package com.vrgames.fishing.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Simulated hands (debug builds' `hands <scenario>` on the emulator, which has no hand tracking, and the unit tests):
 * a player's motions as tracker readings with timestamps, at [RATE] readings a second.
 *
 * The hand is a plain anatomical model with its OWN fixed orientation (fingers forward and a little down, palm facing
 * in, thumb up) plus noise in position and orientation — NOT built from the game's rod or hand model. (0.4.0's
 * emulator "pass" used hands built from the code's own rod model, fresh every frame, with no orientation noise, so it
 * could only agree with the code.)
 *
 * Scenarios: fist · cast · cast60 (every 3rd reading repeated, stamp unchanged) · freeze (the data freezes in the
 * middle of the throw) · noisy (held cocked and still with 3 mm / 3° noise) · lapnet (the left hand resting by the
 * net, its relaxed fingers flickering across the fist threshold) · reelface / reelpedal / reelsmall (circling in the
 * facing plane, the bicycle-pedal plane, small 3.5 cm circles) · reelonly (reel all the way, never the net) ·
 * tutorial (the whole chain: take, cock, throw, wait, strike by lifting, reel, net).
 */
class SimHands(val scenario: String, seed: Long = 11) {
    class Frame(val right: HandReading?, val left: HandReading?, val stamp: Long)

    var log: (String) -> Unit = {}
    var t = 0.0; private set
    private val rnd = java.util.Random(seed)
    private var stamp = 5_000_000_000L
    private var n = 0
    private var last: Frame? = null
    private val r = Mover(LAP_R)
    private val l = Mover(LAP_L)
    private var rClosed = 0.0
    private var lClosed = 0.0
    private var phase = "start"
    private var phaseT = 0.0
    private var lPhase = "rest"
    private var lPhaseT = 0.0
    private var angle = 0.0
    private var frozenUntil = -1.0
    private var froze = false
    private var noise = 0.001
    private var jitter = 1.0
    private var throwT = -1.0
    private var reach = Vec3.ZERO
    private var said = HashSet<String>()

    private fun say(key: String, s: String) { if (said.add(key)) log("sim $scenario: $s") }

    /** The next reading [dt] s on, for the [w]orld, the [a]ngler and the [trip] as they are. */
    fun next(dt: Double, w: Angler.World, a: Angler, trip: Trip?): Frame {
        t += dt
        phaseT += dt
        lPhaseT += dt
        control(dt, w, a, trip)
        r.step(dt); l.step(dt)
        n++
        val prev = last
        // A repeated reading: the same joints and the same stamp (the tracker gave nothing new).
        if (prev != null && (scenario == "cast60" && n % 3 == 0 || t < frozenUntil)) return prev
        // The tracker stamps a reading with the time it was taken (a repeated one keeps its old stamp).
        stamp = 5_000_000_000L + (t * 1e9).toLong()
        val f = Frame(hand(r.p, rClosed, 1), hand(l.p, lClosed, -1), stamp)
        last = f
        return f
    }

    private fun go(p: String) { phase = p; phaseT = 0.0 }
    private fun goL(p: String) { lPhase = p; lPhaseT = 0.0 }

    private fun control(dt: Double, w: Angler.World, a: Angler, trip: Trip?) {
        val st = w.state
        val castScenario = scenario in setOf("cast", "cast60", "freeze", "noisy", "tutorial", "fist")
        if (scenario == "noisy" && phase == "cocked") { noise = 0.003; jitter = 3.0 } else { noise = 0.001; jitter = 1.0 }
        // ---- The right hand: the rod ----
        if (scenario == "tutorial" && st == Trip.State.LANDED && trip != null && trip.catches.isNotEmpty() && a.net == Angler.Net.REST) {
            if (phase != "idle") { say("done", "landed ${trip.catches.size} fish (by ${trip.landedBy})"); rClosed = 0.0; r.go(LAP_R, 0.6); go("idle") }
        } else if (castScenario && (st == Trip.State.READY || st == Trip.State.LANDED)) {
            when (phase) {
                "start", "rest" -> if (phaseT > 0.4 && !(scenario != "tutorial" && said.contains("done"))) {
                    if (a.rod == Angler.Rod.HOLDER) { r.go(w.rodGrip + Vec3(0.02, -0.03, 0.05), 0.6); rClosed = 0.0; go("reach") } else go("cock")
                }
                "reach" -> if (r.done) { go("close") }
                "close" -> {
                    rClosed = (phaseT / 0.15).coerceAtMost(1.0)
                    if (a.rod == Angler.Rod.RIGHT) { say("taken", "the rod is in the right hand (fist)"); go(if (scenario == "fist") "hold" else "cock0") }
                    else if (phaseT > 0.8) { rClosed = 0.0; r.go(r.p + Vec3(0.0, -0.05, 0.15), 0.4); go("retry") }
                }
                "retry" -> if (r.done) go("rest")
                "hold" -> if (phaseT > 1.0) { say("done", "rod held ${a.rod}, no cast (fist only)"); go("idle") }
                "cock0" -> { r.go(COCK, 0.5); go("cock") }
                "cock" -> if (r.done) go("cocked")
                "cocked" -> {
                    if (scenario == "noisy") {
                        if (phaseT > 5.0) { say("done", "held cocked and still 5 s with 3 mm / 3° noise; cast phase ${a.castPhase}"); go("idle") }
                    } else if (phaseT > 0.3) { r.go(THROW_END, THROW_S); throwT = t; go("throw") }
                }
                "throw" -> {
                    if (scenario == "freeze" && !froze && phaseT > THROW_S * 0.4) { froze = true; frozenUntil = t + 0.3; say("froze", "tracking frozen 0.3 s in the middle of the throw") }
                    if (r.done) go("after")
                }
                "after" -> if (phaseT > 0.5) {
                    say("done", "throw finished; rod ${a.rod}, cast phase ${a.castPhase}")
                    if (a.rod == Angler.Rod.HOLDER) { rClosed = 0.0; r.go(LAP_R, 0.6); go("rest") } else go("idle")
                }
                "idle" -> Unit
                else -> if (phaseT > 0.3) go("rest")
            }
        } else if (st == Trip.State.WAIT) {
            if (phase != "lap") { rClosed = 0.0; r.go(LAP_R, 0.6); go("lap") }
        } else if (st == Trip.State.BITE && scenario == "tutorial") {
            when (phase) {
                "near" -> if (r.done) { r.go(r.p + Vec3(0.0, 0.15, 0.0), 0.18); go("lift") }
                "lift" -> if (phaseT > 1.2) go("strike")
                else -> { rClosed = 0.0; r.go(w.rodGrip + Vec3(0.03, -0.07, 0.06), 0.5); go("near") }
            }
        } else if (st == Trip.State.FIGHT || st == Trip.State.NETTABLE) {
            val reels = scenario.startsWith("reel") || scenario == "tutorial"
            val stopForNet = scenario == "tutorial" && st == Trip.State.NETTABLE
            if (reels && !stopForNet) {
                val (u, v, rad, tps) = circle()
                val calm = trip == null || (trip.running <= 0 && trip.tension < 0.72)
                if (phase != "circle" && phase != "circle0") { rClosed = 0.0; r.go(CIRCLE_C + u * rad, 0.4); go("circle0") }
                else if (phase == "circle0" && r.done) go("circle")
                else if (phase == "circle") {
                    if (calm) angle += 2 * Math.PI * tps * dt
                    r.set(CIRCLE_C + u * (rad * cos(angle)) + v * (rad * sin(angle)))
                }
            } else if (phase != "lap") { rClosed = 0.0; r.go(LAP_R, 0.6); go("lap") }
        } else if (phase != "lap" && phase != "idle") { rClosed = 0.0; r.go(LAP_R, 0.6); go("lap") }

        // ---- The left hand: the net ----
        if (scenario == "lapnet") {
            if (lPhase != "lapnet") { l.go(w.netGrip + Vec3(-0.01, -0.10, 0.06), 0.5); goL("lapnet") }
            // Relaxed fingers drifting across the fist threshold three times a second.
            lClosed = 0.5 + 0.38 * sin(t * 2 * Math.PI * 3) + 0.05 * rnd.nextGaussian()
            if (lPhaseT > 6.0) say("lap-${st.name}", "left hand resting by the net with flickering fingers 6 s in $st; net ${a.net}")
        } else if (scenario == "tutorial" && st == Trip.State.NETTABLE) {
            when (lPhase) {
                "rest", "lap" -> { lClosed = 0.0; l.go(w.netGrip + Vec3(0.0, -0.02, 0.05), 0.7); goL("reach") }
                "reach" -> if (l.done) goL("close")
                "close" -> {
                    lClosed = (lPhaseT / 0.15).coerceAtMost(1.0)
                    if (a.net == Angler.Net.LEFT) { say("net", "the net is in the left hand (fist)"); reach = NetScoop.Pose.handFor(w.fish + Vec3(0.0, -0.18, 0.0), Layout.SHOULDER_L, l.p); l.go(reach, 0.8); goL("out") }
                    else if (lPhaseT > 0.8) { lClosed = 0.0; l.go(l.p + Vec3(-0.05, -0.05, 0.15), 0.4); goL("back") }
                }
                "back" -> if (l.done) goL("rest")
                "out" -> if (l.done && lPhaseT > 1.1) { l.go(l.p + Vec3(0.0, -0.06, 0.0), 0.2); goL("press") }
                "press" -> if (l.done) { l.go(l.p + Vec3(0.0, 0.25, 0.0), 0.4); goL("lift") }
                "lift" -> if (l.done && lPhaseT > 0.8) { if (a.net == Angler.Net.LEFT) { l.go(reach, 0.5); goL("out") } else goL("rest") }
            }
        } else if (lPhase != "lap") { lClosed = 0.0; l.go(LAP_L, 0.6); goL("lap") }
    }

    private data class Circle(val u: Vec3, val v: Vec3, val r: Double, val tps: Double)

    private fun circle(): Circle = when (scenario) {
        "reelpedal" -> Circle(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), 0.08, 1.2)
        "reelsmall" -> Circle(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), 0.035, 1.5)
        else -> Circle(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), 0.08, 1.2)
    }

    /**
     * A hand at [palm], [closed] 0 (open) .. 1 (a fist), [side] +1 right / -1 left, from its own frame: fingers
     * forward and a little down and in, thumb up and in; turned by a small random rotation ([jitter] degrees).
     */
    private fun hand(palm0: Vec3, closed: Double, side: Int): HandReading {
        val palm = palm0 + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * noise
        var f = Vec3(-0.12 * side, -0.2, -1.0).normalized()
        var th = Vec3(-0.35 * side, 1.0, 0.0)
        th = (th - f * th.dot(f)).normalized()
        if (jitter > 0) {
            val ax = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalized()
            val ang = Math.toRadians(jitter * rnd.nextGaussian())
            f = rotate(f, ax, ang); th = rotate(th, ax, ang)
        }
        val palmSide = f.cross(th).normalized() * side.toDouble()
        val c = closed.coerceIn(0.0, 1.0)
        fun tip(len: Double, across: Double): Vec3 {
            val open = palm + f * len + th * across
            val shut = palm + f * 0.03 + palmSide * 0.028 + th * (across * 0.5)
            return open * (1 - c) + shut * c
        }
        return HandReading(
            palm = palm,
            wrist = palm - f * 0.045,
            indexTip = tip(0.088, 0.02),
            thumbTip = palm + f * (0.05 - 0.01 * c) + th * (0.045 - 0.015 * c),
            middleTip = tip(0.095, 0.0),
            ringTip = tip(0.088, -0.02),
        )
    }

    private fun rotate(v: Vec3, k: Vec3, a: Double): Vec3 = v * cos(a) + k.cross(v) * sin(a) + k * (k.dot(v) * (1 - cos(a)))

    /** A point moving to targets on smooth (minimum-jerk) paths. */
    private class Mover(var p: Vec3) {
        private var from = p
        private var to = p
        private var dur = 0.0
        private var el = 0.0
        val done get() = el >= dur
        fun go(target: Vec3, seconds: Double) { from = p; to = target; dur = seconds; el = 0.0 }
        fun set(q: Vec3) { p = q; from = q; to = q; dur = 0.0; el = 0.0 }
        fun step(dt: Double) {
            if (el >= dur) { p = to; return }
            el += dt
            val u = (el / dur).coerceIn(0.0, 1.0)
            val s = u * u * u * (10 - 15 * u + 6 * u * u)
            p = from * (1 - s) + to * s
        }
    }

    companion object {
        const val RATE = 90.0
        val LAP_R = Vec3(0.2, Layout.EYE - 0.6, -0.35)
        val LAP_L = Vec3(-0.2, Layout.EYE - 0.6, -0.35)
        /** Over the shoulder and back (the cock): 6 cm under the eyes, 34 cm out. */
        val COCK = Vec3(0.22, Layout.EYE - 0.06, -0.34)
        /** The throw's end: in front of the chest, 72 cm out; 0.28 s on a smooth path (peak about 2.6 m/s). */
        val THROW_END = Vec3(0.15, Layout.EYE - 0.28, -0.72)
        const val THROW_S = 0.28
        val CIRCLE_C = Vec3(0.15, Layout.EYE - 0.32, -0.38)
        val SCENARIOS = listOf("fist", "cast", "cast60", "freeze", "noisy", "lapnet", "reelface", "reelpedal", "reelsmall", "reelonly", "tutorial")
    }
}
