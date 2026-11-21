package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.5 winding by circling (device test 2026-11-20 on 0.4.0: 「不会收线，在绕线轮旁边画圈几乎不动」). Ported from the
 * investigation's reelsim.py, where 0.4's detector (the angle about the reel's left-right axle only) gave 0 turns for a
 * circle in the facing plane and for small finger circles. Now any plane, anywhere in front, 3–25 cm.
 */
class CircleReelTest {
    private val c0 = Vec3(0.1, Layout.EYE - 0.35, -0.4)
    private val x = Vec3(1.0, 0.0, 0.0)
    private val y = Vec3(0.0, 1.0, 0.0)
    private val z = Vec3(0.0, 0.0, 1.0)

    /**
     * Circles in the plane of [u], [v] at [r] m, [tps] turns a second for [seconds] after [rest] s still, read at [hz];
     * every [dup]-th reading repeated (not new: skipped, as the tracker gives nothing new); [noise] m. Turns wound.
     */
    private fun circle(u: Vec3, v: Vec3, r: Double, tps: Double = 1.2, seconds: Double = 5.0, rest: Double = 0.5, hz: Double = 90.0, noise: Double = 0.0, dup: Int = 0, wiggle: Boolean = false): Double {
        val c = CircleReel()
        val rnd = Random(2)
        var t = 0.0
        var k = 0
        var turns = 0.0
        while (t < seconds + rest) {
            t += 1 / hz
            k++
            if (dup > 0 && k % dup == 0) { c.idle(t); continue }
            val tt = (t - rest).coerceAtLeast(0.0)
            val a = 2 * Math.PI * tps * tt
            val p = if (wiggle) c0 + u * (r * sin(a)) else c0 + u * (r * cos(a)) + v * (r * sin(a))
            turns += c.update(t, p + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * noise)
        }
        return turns
    }

    private fun near(expected: Double, got: Double, what: String) =
        assertTrue("$what: $got of $expected turns", got > expected * 0.8 && got <= expected + 0.05)

    @Test fun aCircleInTheFacingPlaneWinds() = near(6.0, circle(x, y, 0.08), "facing")

    @Test fun aCircleInThePedalPlaneWinds() = near(6.0, circle(y, z, 0.08), "pedal")

    @Test fun aCircleLyingFlatWinds() = near(6.0, circle(x, z, 0.08), "flat")

    @Test fun smallCirclesWind() {
        near(7.5, circle(x, y, 0.03, tps = 1.5), "3 cm")
        near(6.0, circle(y, z, 0.04), "4 cm pedal")
    }

    @Test fun slowAndFastCirclesWind() {
        near(4.0, circle(x, y, 0.08, tps = 0.5, seconds = 8.0), "half a turn a second")
        near(12.5, circle(x, y, 0.10, tps = 2.5), "2.5 turns a second")
        near(4.0, circle(y, z, 0.20, tps = 0.8), "20 cm")
    }

    @Test fun eitherDirectionWinds() = near(6.0, circle(y, x, 0.08), "the other way round")

    @Test fun noisyCirclesStillWind() {
        near(6.0, circle(x, y, 0.08, noise = 0.003), "8 cm, 3 mm noise")
        near(7.5, circle(x, y, 0.03, tps = 1.5, noise = 0.003), "3 cm, 3 mm noise")
    }

    @Test fun repeatedReadingsStillWind() = near(6.0, circle(x, y, 0.08, dup = 3), "every 3rd reading repeated")

    @Test fun aBackAndForthWiggleWindsNothing() {
        assertEquals(0.0, circle(x, y, 0.08, tps = 1.5, wiggle = true, noise = 0.002), 1e-9)
        assertEquals(0.0, circle(y, z, 0.15, tps = 1.0, wiggle = true, noise = 0.003), 1e-9)
        assertEquals(0.0, circle(z, y, 0.06, tps = 2.0, wiggle = true, noise = 0.003), 1e-9)
    }

    @Test fun aStillHandWindsNothing() = assertEquals(0.0, circle(x, y, 0.0, noise = 0.003, seconds = 10.0), 1e-9)

    @Test fun aWiderOrSmallerCircleThanAHandMakesIsNotAReel() {
        assertEquals(0.0, circle(x, y, 0.4, tps = 0.5), 1e-9)
        assertEquals(0.0, circle(x, y, 0.01, tps = 2.0), 1e-9)
    }
}
