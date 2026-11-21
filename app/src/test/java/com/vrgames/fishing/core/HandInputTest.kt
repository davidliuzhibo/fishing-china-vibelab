package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * v0.5's input layer (device test 2026-11-20 on 0.4.0: pinch-and-lift never took the rod, a fist only sometimes; the
 * net jumped into a hand resting on the thigh): fresh readings by the tracker's stamp, the fist with hysteresis, a
 * deliberate grab, the strike by lifting.
 */
class HandInputTest {
    /** A hand at [palm] with its middle and ring tips [curl] m from the palm (open ~0.095, a fist ~0.04). */
    private fun hand(palm: Vec3, curl: Double = 0.095) = HandReading(
        palm = palm, wrist = palm + Vec3(0.0, 0.0, 0.045),
        indexTip = palm + Vec3(0.01, 0.0, -0.085), thumbTip = palm + Vec3(-0.02, 0.03, -0.05),
        middleTip = palm + Vec3(0.0, 0.0, -curl), ringTip = palm + Vec3(-0.015, 0.0, -curl * 0.95),
    )

    @Test
    fun onlyNewStampedReadingsThatMoveCount() {
        val h = HandTrack("right")
        val p = Vec3(0.2, 0.9, -0.4)
        h.update(0.0, 0.0, hand(p), stampNew = true)
        assertTrue(h.isNew && h.fresh)
        // The same stamp again (a repeated reading): nothing new.
        h.update(0.011, 0.011, hand(p + Vec3(0.001, 0.0, 0.0)), stampNew = false)
        assertFalse(h.isNew)
        // A new stamp but the very same joints (the tracker froze while its clock ran on): nothing new either…
        val frozen = hand(p + Vec3(0.002, 0.0, 0.0))
        h.update(0.022, 0.022, frozen, stampNew = true)
        assertTrue(h.isNew)
        for (k in 1..20) h.update(0.022 + k * 0.011, 0.022 + k * 0.011, frozen, stampNew = true)
        // …and after 0.15 s of it the hand counts as gone.
        assertFalse(h.isNew)
        assertFalse(h.fresh)
        assertTrue(h.lostFor(0.022 + 20 * 0.011) > HandTrack.FRESH_S)
    }

    @Test
    fun theStampsUnitIsLearnedAndReadingsGetItsTimes() {
        val c = StampClock()
        var stamp = 7_000_000_000L
        var t = 0.0
        var last = 0.0
        // 72 Hz readings arriving with frame jitter (render at 90 Hz): after a second the stamp's own times are used.
        repeat(200) { k ->
            stamp += 13_888_889L
            t += 0.0139 + (if (k % 2 == 0) 0.004 else -0.004)
            last = c.time(t, stamp)
        }
        assertEquals(1e9, c.scale, 1.0)
        assertTrue(c.isNew(stamp + 1))
        assertFalse(c.isNew(stamp))
        val next = c.time(t + 0.02, stamp + 13_888_889L)
        assertEquals(0.013889, next - last, 1e-4)
    }

    @Test
    fun theFistHasHysteresis() {
        val h = HandTrack("left")
        val p = Vec3(-0.2, 0.7, -0.4)
        var t = 0.0
        fun at(curl: Double) { t += 0.011; h.update(t, t, hand(p + Vec3(0.0, 1e-4 * (t * 1000 % 3), 0.0), curl), true) }
        at(0.095)
        assertFalse(h.fist)
        // Relaxed fingers drifting between 6.5 and 7.5 cm: no change either way.
        var edges = 0
        repeat(200) { k -> at(if (k % 2 == 0) 0.065 else 0.075); if (h.fistEdge) edges++ }
        assertEquals(0, edges)
        assertFalse(h.fist)
        at(0.055)
        assertTrue(h.fist && h.fistEdge)
        repeat(50) { k -> at(if (k % 2 == 0) 0.065 else 0.075) }
        assertTrue("stays closed between the thresholds", h.fist)
        at(0.085)
        assertFalse(h.fist)
    }

    @Test
    fun aDeliberateFistTakesTheGrip() {
        val g = Grab()
        val h = HandTrack("right")
        val target = Layout.gripTarget(Layout.REST_GRIP, Layout.dirOf(Layout.READY_PITCH, 0.0))
        val lap = Vec3(0.2, Layout.EYE - 0.6, -0.35)
        var t = 0.0
        var took = false
        fun at(p: Vec3, curl: Double) { t += 1 / 90.0; h.update(t, t, hand(p, curl), true); if (g.update(h, target)) took = true }
        repeat(30) { at(lap, 0.095) }
        // Reach in 0.6 s, close in 0.15 s.
        for (k in 1..54) { val u = k / 54.0; at(lap + (target + Vec3(0.02, -0.03, 0.05) - lap) * (u * u * (3 - 2 * u)), 0.095) }
        for (k in 1..14) at(target + Vec3(0.02, -0.03, 0.05), 0.095 - 0.055 * k / 14.0)
        assertTrue(took)
    }

    @Test
    fun aHandThatPausesAtTheGlowBeforeClosingStillTakes() {
        val g = Grab()
        val h = HandTrack("right")
        val target = Layout.REST_GRIP
        val from = target + Vec3(0.05, -0.25, 0.15)
        var t = 0.0
        var took = false
        fun at(p: Vec3, curl: Double) { t += 1 / 90.0; h.update(t, t, hand(p, curl), true); if (g.update(h, target)) took = true }
        for (k in 1..54) at(from + (target + Vec3(0.0, -0.04, 0.04) - from) * (k / 54.0), 0.095)
        // A second's pause in reach (a live hand jitters a little), then the hand closes.
        repeat(90) { k -> at(target + Vec3(0.0003 * (k % 2), -0.04, 0.04), 0.095) }
        for (k in 1..14) at(target + Vec3(0.0003 * (k % 2), -0.04, 0.04), 0.095 - 0.055 * k / 14.0)
        assertTrue(took)
    }

    @Test
    fun aHandRestingNearItWithFlickeringFingersNeverTakesIt() {
        // 0.4.0: the left hand on the thigh, 12 cm from the net's grip, its relaxed fingers flickering, took the net.
        val g = Grab()
        val h = HandTrack("left")
        val target = Layout.NET_GRIP
        val rest = target + Vec3(-0.01, -0.10, 0.06)
        val rnd = Random(5)
        var t = 0.0
        var took = 0
        repeat(90 * 60) { k ->
            t += 1 / 90.0
            val curl = 0.07 + 0.022 * kotlin.math.sin(k * 0.2) + 0.004 * rnd.nextGaussian()
            h.update(t, t, hand(rest + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * 0.004, curl), true)
            if (g.update(h, target)) took++
        }
        assertEquals(0, took)
    }

    @Test
    fun aFistFarFromItOrComingBackClosedLongAfterDoesNotTake() {
        val g = Grab()
        val h = HandTrack("right")
        val target = Layout.REST_GRIP
        var t = 0.0
        fun at(p: Vec3?, curl: Double): Boolean { t += 1 / 90.0; h.update(t, t, p?.let { hand(it, curl) }, true); return g.update(h, target) }
        // Reaching for something 30 cm off and closing there.
        val off = target + Vec3(0.3, 0.0, 0.0)
        for (k in 1..54) assertFalse(at(off + Vec3(0.0, -0.3 * (1 - k / 54.0), 0.0), 0.095))
        for (k in 1..14) assertFalse(at(off, 0.095 - 0.055 * k / 14.0))
        // Out of tracking 2 s, back closed right at the grip: no approach seen, no take.
        repeat(180) { at(null, 0.04) }
        repeat(30) { assertFalse(at(target, 0.04)) }
    }

    @Test
    fun aQuickLiftNearTheGripStrikes() {
        val grip = Layout.restGrip(Layout.WAIT_PITCH, 0.0)
        fun lift(from: Vec3, rise: Double, seconds: Double): Boolean {
            val h = HandTrack("right")
            var t = 0.0
            var hit = false
            repeat(30) { t += 1 / 90.0; h.update(t, t, hand(from), true) }
            val n = (seconds * 90).toInt()
            for (k in 1..n) { t += 1 / 90.0; h.update(t, t, hand(from + Vec3(0.0, rise * k / n, 0.0)), true); if (StrikeLift.check(h, grip)) hit = true }
            repeat(30) { t += 1 / 90.0; h.update(t, t, hand(from + Vec3(0.0, rise, 0.0)), true); if (StrikeLift.check(h, grip)) hit = true }
            return hit
        }
        assertTrue("12 cm in 0.2 s beside the grip", lift(grip + Vec3(0.04, -0.06, 0.06), 0.12, 0.2))
        assertFalse("too slow: 12 cm in 1 s", lift(grip + Vec3(0.04, -0.06, 0.06), 0.12, 1.0))
        assertFalse("too small: 6 cm", lift(grip + Vec3(0.04, -0.06, 0.06), 0.06, 0.2))
        assertFalse("far from the grip (the lap)", lift(Vec3(0.2, Layout.EYE - 0.6, -0.35), 0.15, 0.2))
    }

    @Test
    fun whileTheFloatOnlyNibblesReachingUpFromTheLapIsNoStrike() {
        val grip = Layout.restGrip(Layout.WAIT_PITCH, 0.0)
        val lap = Vec3(0.2, Layout.EYE - 0.6, -0.35)
        val near = grip + Vec3(0.03, -0.07, 0.06)
        val h = HandTrack("right")
        var t = 0.0
        var nibble = false
        var bite = false
        fun at(p: Vec3) { t += 1 / 90.0; h.update(t, t, hand(p), true); if (StrikeLift.check(h, grip, dwell = true)) nibble = true; if (StrikeLift.check(h, grip)) bite = true }
        repeat(30) { at(lap) }
        for (k in 1..45) { val u = k / 45.0; at(lap + (near - lap) * (u * u * (3 - 2 * u))) }
        repeat(10) { at(near) }
        assertFalse("reaching up to the grip during a nibble", nibble)
        assertTrue("the same reach once the float is down", bite)
        // Already at the grip, a quick lift strikes either way.
        repeat(40) { at(near + Vec3(0.0, 0.0, 1e-4 * (it % 2))) }
        for (k in 1..16) at(near + Vec3(0.0, 0.14 * k / 16, 0.0))
        assertTrue(nibble)
    }
}
