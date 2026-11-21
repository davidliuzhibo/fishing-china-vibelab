package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * v0.5's two-step cast from palm positions (device test 2026-11-20 on 0.4.0: the rod went behind the head and cast
 * itself to near water; a forward throw sent the line the wrong way). Ported from the investigation's castsim.py: the
 * cases that broke 0.4 (a rod held still with orientation jitter self-cast within 0.3 s; repeated frames released early;
 * tracking lost mid-throw still cast) must not happen here.
 */
class OverheadCastTest {
    private val grip = Layout.REST_GRIP
    private val cock = Vec3(0.22, Layout.EYE - 0.06, -0.34)

    private fun smooth(u: Double) = u * u * u * (10 - 15 * u + 6 * u * u)

    /**
     * Picks the rod up at [grip], raises it to [cockAt] in 0.5 s, holds [hold] s, throws to [end] in [throwS] s on a
     * smooth path, holds 0.5 s. [hz] readings a second, [noise] metres of palm noise. Returns every result in order.
     */
    private fun run(end: Vec3, throwS: Double, cockAt: Vec3 = cock, hold: Double = 0.25, hz: Double = 90.0, noise: Double = 0.0, seed: Long = 3): List<OverheadCast.Result> {
        val c = OverheadCast()
        val rnd = Random(seed)
        val out = ArrayList<OverheadCast.Result>()
        var t = 0.0
        val dt = 1 / hz
        fun at(p: Vec3) { t += dt; c.sample(t, p + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * noise)?.let { out += it } }
        c.begin(grip)
        repeat((0.3 * hz).toInt()) { at(grip) }
        val n1 = (0.5 * hz).toInt()
        for (k in 1..n1) at(grip + (cockAt - grip) * smooth(k.toDouble() / n1))
        repeat((hold * hz).toInt()) { at(cockAt) }
        val n2 = (throwS * hz).toInt().coerceAtLeast(2)
        for (k in 1..n2) at(cockAt + (end - cockAt) * smooth(k.toDouble() / n2))
        repeat((0.5 * hz).toInt()) { at(end) }
        return out
    }

    private val ahead = Vec3(0.15, Layout.EYE - 0.28, -0.72)

    @Test
    fun aCastIsTheCockThenAThrow() {
        val r = run(ahead, 0.28)
        assertTrue(r.toString(), r.first() is OverheadCast.Result.Cocked)
        val c = r.filterIsInstance<OverheadCast.Result.Cast>().single()
        assertTrue("distance ${c.distance}", c.distance in 8.0..16.0)
        assertEquals("yaw ${c.yaw}", 0.0, c.yaw, 3.0)
    }

    @Test
    fun theFasterTheThrowTheFurtherTheCast() {
        val d = listOf(0.55, 0.45, 0.35, 0.28, 0.22, 0.18).map { s -> run(ahead, s).filterIsInstance<OverheadCast.Result.Cast>().single().distance }
        for (i in 1 until d.size) assertTrue("distances $d", d[i] >= d[i - 1] - 1e-9)
        assertTrue("distances $d", d.last() - d.first() > 6)
        assertTrue(d.all { it in OverheadCast.MIN_D..OverheadCast.MAX_D })
    }

    @Test
    fun aSlowPushIsNoCastButTooSlow() {
        val r = run(ahead, 1.4)
        assertTrue(r.toString(), r.none { it is OverheadCast.Result.Cast })
        assertTrue(r.toString(), r.any { it is OverheadCast.Result.Fail && it.why == "slow" })
    }

    @Test
    fun aSidewaysThrowTurnsTheCastThatWay() {
        val left = run(Vec3(-0.25, Layout.EYE - 0.28, -0.66), 0.28).filterIsInstance<OverheadCast.Result.Cast>().single()
        val right = run(Vec3(0.55, Layout.EYE - 0.28, -0.62), 0.28).filterIsInstance<OverheadCast.Result.Cast>().single()
        assertTrue("left yaw ${left.yaw}", left.yaw < -10)
        assertTrue("right yaw ${right.yaw}", right.yaw > 10)
        assertTrue(left.yaw >= -OverheadCast.MAX_YAW && right.yaw <= OverheadCast.MAX_YAW)
    }

    @Test
    fun withoutTheCockAFastForwardMoveIsNoCast() {
        // Straight from the grip forward and back, fast: never raised over the shoulder.
        val c = OverheadCast()
        c.begin(grip)
        var t = 0.0
        val out = ArrayList<OverheadCast.Result>()
        repeat(3) {
            for (k in 1..20) { t += 1 / 90.0; c.sample(t, grip + Vec3(0.0, 0.0, -0.3 * smooth(k / 20.0)))?.let { out += it } }
            for (k in 1..20) { t += 1 / 90.0; c.sample(t, grip + Vec3(0.0, 0.0, -0.3 + 0.3 * smooth(k / 20.0)))?.let { out += it } }
        }
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun lostTrackingInTheThrowCancelsIt() {
        val c = OverheadCast()
        c.begin(grip)
        var t = 0.0
        val out = ArrayList<OverheadCast.Result>()
        fun at(p: Vec3) { t += 1 / 90.0; c.sample(t, p)?.let { out += it } }
        for (k in 1..45) at(grip + (cock - grip) * smooth(k / 45.0))
        repeat(25) { at(cock) }
        assertTrue(out.single() is OverheadCast.Result.Cocked)
        // A third of the way into the throw the readings stop...
        for (k in 1..8) at(cock + (ahead - cock) * smooth(k / 25.0))
        assertTrue(c.throwing)
        val r = c.gap(0.15, drag = false)
        assertTrue("$r", r is OverheadCast.Result.Fail && r.why == "lost")
        // ...and come back where the hand ended up: nothing more (it has to be raised again).
        repeat(60) { t += 1 / 90.0; assertFalse(c.sample(t, ahead) is OverheadCast.Result.Cast) }
    }

    @Test
    fun heldStillWithNoiseNeverCasts() {
        // Held at the grip and then cocked, 20 s each, with 3 mm of palm noise: 0.4 threw itself within 0.3 s.
        val c = OverheadCast()
        c.begin(grip)
        val rnd = Random(9)
        var t = 0.0
        val out = ArrayList<OverheadCast.Result>()
        for (p in listOf(grip, cock)) repeat(20 * 90) {
            t += 1 / 90.0
            c.sample(t, p + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.003)?.let { out += it }
        }
        assertTrue(out.toString(), out.none { it is OverheadCast.Result.Cast })
    }

    @Test
    fun aThrowWithPalmNoiseStillCasts() {
        val c = run(ahead, 0.28, noise = 0.003).filterIsInstance<OverheadCast.Result.Cast>().single()
        assertTrue("distance ${c.distance}", c.distance > 8)
    }

    @Test
    fun theDragThrowCastsTheSameWay() {
        // No hand tracking: the virtual palm moved by the drag's events; the drag stops (no more events) at the end.
        val c = OverheadCast()
        c.begin(grip)
        val out = ArrayList<OverheadCast.Result>()
        var t = 0.0
        val up = grip + Vec3(0.0, 0.2, 0.12)
        for (k in 1..40) { t += 0.0125; c.sample(t, grip + (up - grip) * smooth(k / 40.0))?.let { out += it } }
        // Still: the drag sends nothing; time passes.
        c.gap(0.15, drag = true)?.let { out += it }
        assertTrue(out.toString(), out.singleOrNull() is OverheadCast.Result.Cocked)
        t += 0.15
        val end = up + Vec3(-0.04, -0.22, -0.35)
        for (k in 1..28) { t += 0.01; c.sample(t, up + (end - up) * smooth(k / 28.0))?.let { out += it } }
        c.gap(0.1, drag = true)?.let { out += it }
        val cast = out.filterIsInstance<OverheadCast.Result.Cast>().singleOrNull()
        assertNotNull(out.toString(), cast)
        assertTrue("distance ${cast!!.distance}", cast.distance > 8)
    }

    @Test
    fun takenFromTheLapTheCockIsALiftAndAPullBack() {
        val lap = Vec3(0.2, Layout.EYE - 0.6, -0.35)
        val c = OverheadCast()
        c.begin(lap)
        var t = 0.0
        val out = ArrayList<OverheadCast.Result>()
        // Raised 20 cm and pulled 9 cm back toward the body, held: the 「咔」.
        val raised = lap + Vec3(0.0, 0.20, 0.09)
        for (k in 1..45) { t += 1 / 90.0; c.sample(t, lap + (raised - lap) * smooth(k / 45.0))?.let { out += it } }
        repeat(20) { t += 1 / 90.0; c.sample(t, raised)?.let { out += it } }
        assertTrue(out.toString(), out.singleOrNull() is OverheadCast.Result.Cocked)
    }
}
