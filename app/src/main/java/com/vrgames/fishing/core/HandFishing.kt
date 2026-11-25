package com.vrgames.fishing.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.5 input layer (device test 2026-11-20 on 0.4.0: 「不知道怎么拿竿，捏住抬起从来没成功过，握拳偶尔可以」; the net jumped
 * into a resting hand; the rod cast by itself; circling near the reel barely wound).
 *
 * What 0.4 trusted and why it failed on the headset:
 * - the pinch strength from hand tracking (stays low, or stale yet "ready") → never used now; a pinch is the SYSTEM's
 *   gaze-and-pinch event (reliable), with the pinching hand's position;
 * - the hand's orientation from knuckles 6–8 cm apart (degrees of jitter → metres a second at a virtual rod tip) → never
 *   used for directions now; only palm POSITIONS are;
 * - fist without hysteresis (relaxed fingers flickered across 6 cm) → closed below [HandTrack.FIST_CLOSE], open above
 *   [HandTrack.FIST_OPEN], used only to take or put things down;
 * - freshness from "joints unchanged" alone → now the tracker's own timestamp says whether a reading is new, and only new
 *   readings give positions for velocities.
 *
 * All in the seat's frame (x right, y up, -z out over the water), pure geometry, tested on the server.
 */
data class HandReading(
    val palm: Vec3,
    val wrist: Vec3? = null,
    val indexTip: Vec3? = null,
    val thumbTip: Vec3? = null,
    val middleTip: Vec3? = null,
    val ringTip: Vec3? = null,
) {
    /** Between the thumb and index tips (where a pinch closes), else the palm. */
    val pinchPoint: Vec3 get() = if (thumbTip != null && indexTip != null) (thumbTip + indexTip) * 0.5 else palm

    /** How far the middle and ring fingertips are from the palm (the larger); null without them. */
    val curl: Double? get() = if (middleTip == null || ringTip == null) null else max(middleTip.distanceTo(palm), ringTip.distanceTo(palm))

    /** The same joints to a tenth of a micrometre: a frozen reading (a live hand always moves a little). */
    fun sameAs(o: HandReading?): Boolean {
        if (o == null) return false
        fun eq(a: Vec3?, b: Vec3?) = if (a == null || b == null) a == b else abs(a.x - b.x) < 1e-7 && abs(a.y - b.y) < 1e-7 && abs(a.z - b.z) < 1e-7
        return eq(palm, o.palm) && eq(wrist, o.wrist) && eq(indexTip, o.indexTip) && eq(thumbTip, o.thumbTip) && eq(middleTip, o.middleTip) && eq(ringTip, o.ringTip)
    }
}

/**
 * Turns the tracker's timestamp into seconds on our clock, and says whether a reading is new. The unit of the stamp is
 * not documented: after a second of readings it is taken from how fast the stamp runs against our clock (ns, µs or ms);
 * until then, and whenever the two disagree by more than [MAX_DRIFT], the arrival time is used.
 */
class StampClock {
    private var s0 = 0L
    private var t0 = 0.0
    private var have = false
    private var last: Long? = null
    /** Stamp units per second (0: not known yet). */
    var scale = 0.0; private set

    /** True when [stamp] differs from the last one (no stamp at all: true, the joints then decide). */
    fun isNew(stamp: Long?): Boolean = stamp == null || stamp != last

    /** The reading's time: from the stamp once its unit is known, else [t] (when it arrived). */
    fun time(t: Double, stamp: Long?): Double {
        if (stamp == null) return t
        last = stamp
        if (!have) { s0 = stamp; t0 = t; have = true; return t }
        if (scale == 0.0 && t - t0 >= 1.0 && stamp > s0) {
            val r = (stamp - s0) / (t - t0)
            // Only a stamp that runs with our clock (±15%) is trusted; anything else keeps the arrival times.
            for (c in doubleArrayOf(1e9, 1e6, 1e3)) if (r > c * 0.85 && r < c * 1.15) scale = c
        }
        if (scale == 0.0) return t
        val st = t0 + (stamp - s0) / scale
        if (abs(st - t) > MAX_DRIFT) { s0 = stamp; t0 = t; return t }
        return st
    }

    companion object { const val MAX_DRIFT = 0.1 }
}

/**
 * One hand over time: whether its data is fresh (a NEW reading — new stamp and joints that moved — within [FRESH_S]),
 * its fist (with hysteresis), and the recent palm positions of new readings (for velocities and the approach of a grab).
 */
class HandTrack(val name: String) {
    class Sample(val t: Double, val palm: Vec3, val tip: Vec3?)

    var reading: HandReading? = null; private set
    /** This update brought a new reading. */
    var isNew = false; private set
    /** A new reading arrived within [FRESH_S]. */
    var fresh = false; private set
    var fist = false; private set
    /** The fist closed with this reading. */
    var fistEdge = false; private set
    /** The time of the latest new reading (sample time). */
    var t = 0.0; private set
    private var lastNewAt = Double.NEGATIVE_INFINITY
    val hist = ArrayDeque<Sample>()
    /** New readings that arrived, and updates that brought nothing new (frozen), for the log. */
    var newCount = 0; private set
    var staleCount = 0; private set

    val palm: Vec3? get() = reading?.palm

    /**
     * v0.6.0: the hand still closed round a rod (not clearly open): the fingertips nearer the palm than [GRIP_OPEN].
     * Measured on the user's PICO (HandRecorder 0.2.0): an open hand's fingertips are 10.7–11.1 cm from the palm, a
     * fist's 3.9–5.6 cm; a hand loosely round a rod sits between.
     */
    val gripping: Boolean get() = reading?.curl?.let { it < GRIP_OPEN } ?: fist
    /** Clearly open (a kept rod goes back when this lasts). */
    val open: Boolean get() = reading?.curl?.let { it > GRIP_OPEN } ?: false

    /** One update at [now] (our clock) with the reading's sample time [sampleT]; [stampNew]: the tracker's stamp changed. */
    fun update(now: Double, sampleT: Double, r: HandReading?, stampNew: Boolean) {
        fistEdge = false
        if (r == null) {
            isNew = false
            fresh = false
            if (now - lastNewAt > FORGET_S) fist = false
            return
        }
        isNew = stampNew && !r.sameAs(reading)
        reading = r
        if (isNew) {
            newCount++
            if (now - lastNewAt > FORGET_S) fist = false
            lastNewAt = now
            t = sampleT
            hist.addLast(Sample(sampleT, r.palm, r.indexTip))
            while (hist.size > 2 && sampleT - hist.first().t > HIST_S) hist.removeFirst()
            val c = r.curl
            if (c != null) {
                val was = fist
                if (c < FIST_CLOSE) fist = true else if (c > FIST_OPEN) fist = false
                fistEdge = fist && !was
            }
        } else staleCount++
        fresh = now - lastNewAt <= FRESH_S
    }

    /** Seconds since the last new reading arrived. */
    fun lostFor(now: Double) = now - lastNewAt

    fun clearHistory() = hist.clear()

    /** The palm's velocity from the new readings within [window] s of the latest (least squares); null if too few. */
    fun velocity(window: Double = VEL_S): Vec3? = velocityOf(hist.map { it.t to it.palm }, window)

    /** How much nearer to [target] the palm came within the last [within] s (the most, from any earlier reading). */
    fun approach(target: Vec3, within: Double = Grab.APPROACH_S): Double {
        val now = hist.lastOrNull() ?: return 0.0
        val dNow = now.palm.distanceTo(target)
        var best = 0.0
        for (s in hist) if (now.t - s.t <= within) best = max(best, s.palm.distanceTo(target) - dNow)
        return best
    }

    companion object {
        const val FRESH_S = 0.15
        const val HIST_S = 1.0
        const val FORGET_S = 1.0
        /** Fist hysteresis: closed when both fingertips are nearer the palm than this, open when either is farther than [FIST_OPEN]. */
        const val FIST_CLOSE = 0.06
        const val FIST_OPEN = 0.08
        /** v0.6.0: above this the hand has let go of a kept rod (an open hand reads about 0.11). */
        const val GRIP_OPEN = 0.09
        const val VEL_S = 0.05

        /** Least-squares velocity over the samples within [window] s of the last (or the last two when only one is). */
        fun velocityOf(s: List<Pair<Double, Vec3>>, window: Double): Vec3? {
            if (s.size < 2) return null
            val last = s.last().first
            var use = s.filter { last - it.first <= window + 1e-9 }
            if (use.size < 2) use = s.takeLast(2)
            val n = use.size.toDouble()
            val mt = use.sumOf { it.first } / n
            val st = use.sumOf { (it.first - mt) * (it.first - mt) }
            if (st < 1e-9) return null
            var x = 0.0; var y = 0.0; var z = 0.0
            for ((tt, p) in use) { val w = tt - mt; x += w * p.x; y += w * p.y; z += w * p.z }
            return Vec3(x / st, y / st, z / st)
        }
    }
}

/**
 * Taking something by closing the hand round it (v0.5): a deliberate fist — the hand closed within [REACH] of the thing
 * after coming at least [APPROACH] nearer to it within [APPROACH_S]. A hand resting near it (the left hand on the thigh
 * by the net) never comes nearer, so its relaxed fingers can flicker as they like. A hand that came in and hesitates
 * stays "armed" while it keeps within [REACH], up to [HOVER_S] after its approach (a first-timer often pauses at the
 * glow before closing). Feed it every frame for each hand ([update]).
 */
class Grab {
    private val armedAt = HashMap<HandTrack, Double>()

    fun reset() = armedAt.clear()

    /** True when [h] takes the thing at [target] now. */
    fun update(h: HandTrack, target: Vec3): Boolean {
        val palm = h.palm ?: return false
        if (palm.distanceTo(target) >= REACH) { armedAt.remove(h); return false }
        // A moment of stale data (fingers closing hide the hand) keeps the hand armed; only new readings count.
        if (!h.isNew || !h.fresh) return false
        if (h.approach(target) >= APPROACH) armedAt[h] = h.t
        val armed = armedAt[h]?.let { h.t - it <= HOVER_S } ?: false
        if (h.fistEdge && armed) { armedAt.remove(h); return true }
        return false
    }

    companion object {
        const val REACH = 0.18
        const val APPROACH = 0.06
        const val APPROACH_S = 0.6
        const val HOVER_S = 1.5
    }
}

/**
 * Setting the hook by hand (v0.5): a fresh hand within [NEAR] of the rod's grip rising [RISE] within [WINDOW_S]
 * (positions only; 0.4's lever needed a pinch and the hand's turn). While the float only nibbles ([dwell] true) the
 * hand must already have been near the grip [DWELL_S] before the lift, so reaching up from the lap to get ready is not
 * taken for a strike (it would scare the fish off); once the float is down, any such lift strikes.
 */
object StrikeLift {
    const val NEAR = 0.25
    const val RISE = 0.10
    const val WINDOW_S = 0.35
    const val DWELL_S = 0.25

    fun check(h: HandTrack, grip: Vec3, dwell: Boolean = false): Boolean {
        if (!h.fresh || !h.isNew) return false
        val hist = h.hist
        val now = hist.lastOrNull() ?: return false
        // Since when the hand has been near the grip without a break.
        var nearSince = Double.NaN
        for (i in hist.indices.reversed()) { if (hist[i].palm.distanceTo(grip) < NEAR) nearSince = hist[i].t else break }
        for (s in hist) {
            if (now.t - s.t > WINDOW_S || s.palm.distanceTo(grip) >= NEAR || now.palm.y - s.palm.y < RISE) continue
            if (!dwell || (!nearSince.isNaN() && s.t - nearSince >= DWELL_S)) return true
        }
        return false
    }
}

/**
 * Winding by circling a hand (v0.5, device test 2026-11-20: 「不会收线，在绕线轮旁边画圈几乎不动」 — 0.4 only counted the
 * angle about the reel's own left-right axle, so a circle in the facing plane, or small finger circles, wound nothing).
 *
 * Now any circle anywhere in front, in any plane: the plane and centre are fitted to the last [WINDOW_S] of positions
 * (principal axes, then a least-squares circle in that plane); a radius of [R_MIN]..[R_MAX] and a good fit make it a
 * circle. The first [ENGAGE] in one direction only starts the winding (a hand passing by does not wind); after that
 * every bit of circling winds, either direction; noise backwards is paid back before more counts. A straight
 * back-and-forth (a line) never fits, so a wiggle winds nothing. Feed new readings only.
 */
class CircleReel {
    private val ts = ArrayDeque<Double>()
    private val ps = ArrayDeque<Vec3>()
    private var prev: Vec3? = null
    private var normal: Vec3? = null
    private var dir = 0
    private var run = 0.0
    private var credit = 0.0
    private var lastFit = Double.NEGATIVE_INFINITY
    /** Winding now (a circle begun in one direction). */
    val engaged get() = dir != 0
    /** The last fitted radius (m), for the log. */
    var radius = 0.0; private set

    fun reset() { ts.clear(); ps.clear(); prev = null; normal = null; dir = 0; run = 0.0; credit = 0.0; lastFit = Double.NEGATIVE_INFINITY }

    /** No new reading at [t] (the hand is gone or frozen). */
    fun idle(t: Double) {
        if (ts.isNotEmpty() && t - ts.last() > GAP_S) { ts.clear(); ps.clear(); prev = null }
        if (t - lastFit > QUIET_S) { dir = 0; run = 0.0; credit = 0.0 }
    }

    /** A new reading at [t] at [p]: the turns wound by it (≥ 0). */
    fun update(t: Double, p: Vec3): Double {
        if (ts.isNotEmpty() && t - ts.last() > GAP_S) { ts.clear(); ps.clear(); prev = null }
        ts.addLast(t); ps.addLast(p)
        while (ts.size > 2 && t - ts.first() > WINDOW_S) { ts.removeFirst(); ps.removeFirst() }
        val before = prev
        prev = p
        val fit = fit()
        if (fit == null || before == null) {
            if (t - lastFit > QUIET_S) { dir = 0; run = 0.0; credit = 0.0 }
            return 0.0
        }
        lastFit = t
        val (c, u, v) = fit
        fun ang(q: Vec3): Double { val d = q - c; return atan2(d.dot(v), d.dot(u)) }
        var da = ang(p) - ang(before)
        while (da > PI) da -= 2 * PI
        while (da < -PI) da += 2 * PI
        if (abs(da) > JUMP) return 0.0
        if (dir == 0) {
            run += da
            if (abs(run) >= ENGAGE) { dir = if (run > 0) 1 else -1; credit = 0.0 }
            return 0.0
        }
        credit += da * dir
        if (credit > 0) { val out = credit / (2 * PI); credit = 0.0; return out }
        // Turned back a whole start's worth: now winding the other way (either way winds the line).
        if (credit <= -ENGAGE) { dir = -dir; credit = 0.0 }
        return 0.0
    }

    /** The circle through the window: its centre and two axes of its plane; null when the points are no circle. */
    private fun fit(): Triple<Vec3, Vec3, Vec3>? {
        val n = ps.size
        if (n < 6 || ts.last() - ts.first() < MIN_SPAN_S) return null
        var m = Vec3.ZERO
        for (q in ps) m += q
        m *= 1.0 / n
        val cov = Array(3) { DoubleArray(3) }
        for (q in ps) {
            val d = doubleArrayOf(q.x - m.x, q.y - m.y, q.z - m.z)
            for (i in 0..2) for (j in 0..2) cov[i][j] += d[i] * d[j] / n
        }
        val (ev, vec) = Eigen.symmetric3(cov)
        if (ev[0] < 1e-6 || ev[1] < LINE * ev[0]) return null
        var nrm = vec[2]
        normal?.let { if (it.dot(nrm) < 0) nrm = -nrm }
        normal = nrm
        val u = vec[0].normalized()
        val v = nrm.cross(u).normalized()
        // A least-squares circle in the plane (Kåsa): x² + y² + D·x + E·y + F = 0.
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sx = 0.0; var sy = 0.0; var szx = 0.0; var szy = 0.0; var sz = 0.0
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for ((k, q) in ps.withIndex()) {
            val d = q - m
            val x = d.dot(u); val y = d.dot(v)
            xs[k] = x; ys[k] = y
            val z = x * x + y * y
            sxx += x * x; syy += y * y; sxy += x * y; sx += x; sy += y; szx += z * x; szy += z * y; sz += z
        }
        val sol = Eigen.solve3(arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, n.toDouble())), doubleArrayOf(-szx, -szy, -sz)) ?: return null
        val a = -sol[0] / 2; val b = -sol[1] / 2
        val rr = a * a + b * b - sol[2]
        if (rr <= 0) return null
        val r = sqrt(rr)
        if (r < R_MIN || r > R_MAX) return null
        var e = 0.0
        for (k in 0 until n) { val d = hypot(xs[k] - a, ys[k] - b) - r; e += d * d }
        if (sqrt(e / n) > FIT_REL * r + FIT_ABS) return null
        radius = r
        return Triple(m + u * a + v * b, u, v)
    }

    companion object {
        const val WINDOW_S = 0.6
        const val MIN_SPAN_S = 0.2
        const val R_MIN = 0.025
        const val R_MAX = 0.25
        const val LINE = 0.02
        const val FIT_REL = 0.3
        const val FIT_ABS = 0.004
        val ENGAGE = Math.toRadians(90.0)
        val JUMP = Math.toRadians(70.0)
        const val QUIET_S = 0.4
        const val GAP_S = 0.3
    }
}

/** Small linear algebra for [CircleReel]. */
object Eigen {
    /** Eigenvalues (largest first) and unit eigenvectors of a symmetric 3×3 matrix (Jacobi). */
    fun symmetric3(m: Array<DoubleArray>): Pair<DoubleArray, Array<Vec3>> {
        val a = Array(3) { m[it].copyOf() }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(30) {
            var off = 0.0
            for (i in 0..2) for (j in 0..2) if (i != j) off += a[i][j] * a[i][j]
            if (off < 1e-20) return@repeat
            for (p in 0..1) for (q in p + 1..2) {
                if (abs(a[p][q]) < 1e-18) continue
                val th = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (th >= 0) 1.0 else -1.0) / (abs(th) + sqrt(th * th + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0..2) { val kp = a[k][p]; val kq = a[k][q]; a[k][p] = c * kp - s * kq; a[k][q] = s * kp + c * kq }
                for (k in 0..2) { val pk = a[p][k]; val qk = a[q][k]; a[p][k] = c * pk - s * qk; a[q][k] = s * pk + c * qk }
                for (k in 0..2) { val kp = v[k][p]; val kq = v[k][q]; v[k][p] = c * kp - s * kq; v[k][q] = s * kp + c * kq }
            }
        }
        val order = (0..2).sortedByDescending { a[it][it] }
        return DoubleArray(3) { a[order[it]][order[it]] } to Array(3) { k -> val i = order[k]; Vec3(v[0][i], v[1][i], v[2][i]) }
    }

    /** Solves a 3×3 system (Gauss with pivoting); null when singular. */
    fun solve3(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a0[i][0], a0[i][1], a0[i][2], b0[i]) }
        for (i in 0..2) {
            val piv = (i..2).maxByOrNull { abs(m[it][i]) }!!
            if (abs(m[piv][i]) < 1e-14) return null
            val tmp = m[i]; m[i] = m[piv]; m[piv] = tmp
            for (k in 0..2) if (k != i) { val f = m[k][i] / m[i][i]; for (j in 0..3) m[k][j] -= f * m[i][j] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }
}

/**
 * The landing net's scoop (v0.4, kept in v0.5: the standard motion — hoop under the fish, press down, then lift). The
 * fish goes in when the net's hoop, near the fish (within [NEAR] across the water), comes up again after going under
 * it, or after any clear dip ([DIP] down then [RISE] up within [WINDOW_S]); or when the hoop sweeps up through the fish
 * itself (within [THROUGH]).
 */
class NetScoop {
    private val ts = ArrayDeque<Double>()
    private val ys = ArrayDeque<Double>()
    private var lastY = Double.NaN
    private var lastT = 0.0

    fun reset() { ts.clear(); ys.clear(); lastY = Double.NaN }

    /** One frame at [t]: the hoop's centre and the tired fish at the surface. True when the fish is in the net. */
    fun update(t: Double, hoop: Vec3, fish: Vec3): Boolean {
        val flat = hypot(hoop.x - fish.x, hoop.z - fish.z)
        val prevY = lastY
        val dt = t - lastT
        lastY = hoop.y
        lastT = t
        if (flat > NEAR) { ts.clear(); ys.clear(); return false }
        ts.addLast(t); ys.addLast(hoop.y)
        while (ts.isNotEmpty() && t - ts.first() > WINDOW_S) { ts.removeFirst(); ys.removeFirst() }
        // Swept up through the fish.
        if (!prevY.isNaN() && dt > 1e-4 && (hoop.y - prevY) / dt > 0.1 && hoop.distanceTo(fish) < THROUGH) return true
        // The lowest point lately, and the highest before it.
        var lowAt = 0
        for (i in ys.indices) if (ys[i] < ys[lowAt]) lowAt = i
        val low = ys[lowAt]
        var top = low
        for (i in 0 until lowAt) if (ys[i] > top) top = ys[i]
        val under = low <= fish.y - UNDER
        val dipped = top - low >= DIP
        val upAgain = hoop.y - low >= RISE && hoop.y >= fish.y - LEVEL
        return (under || dipped) && upAgain
    }

    companion object {
        const val NEAR = 0.45
        const val UNDER = 0.03
        const val DIP = 0.10
        const val RISE = 0.06
        const val LEVEL = 0.10
        const val THROUGH = 0.15
        const val WINDOW_S = 2.5
    }

    /**
     * Where the hoop is when the net is in a hand: the handle runs out from the hand along the line from the shoulder
     * through the hand, tipped [DOWN_DEG] toward the water. Reaching out takes the net out; lowering the hand dips it;
     * raising the hand lifts it (positions only, no hand orientation).
     */
    object Pose {
        const val HANDLE = 1.25
        const val DOWN_DEG = 22.0
        fun hoop(hand: Vec3, shoulder: Vec3): Vec3 {
            val f = (hand - shoulder).normalized()
            val h = hypot(f.x, f.z)
            val pitch = (if (f == Vec3.ZERO) 0.0 else atan2(f.y, h)) - Math.toRadians(DOWN_DEG)
            val yaw = if (h < 1e-6) 0.0 else atan2(f.x, -f.z)
            val d = Vec3(cos(pitch) * sin(yaw), sin(pitch), -cos(pitch) * cos(yaw))
            return hand + d * HANDLE
        }

        /** The hand's place (from [start], within an arm's reach of [shoulder]) that puts the hoop at [hoop]. */
        fun handFor(hoop: Vec3, shoulder: Vec3, start: Vec3): Vec3 {
            var h = start
            repeat(40) {
                h = h + (hoop - hoop(h, shoulder)) * 0.3
                val d = h - shoulder
                val l = d.length()
                if (l > ARM) h = shoulder + d * (ARM / l)
                if (l < 0.25 && l > 1e-6) h = shoulder + d * (0.25 / l)
            }
            return h
        }

        const val ARM = 0.7
    }
}
