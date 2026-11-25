package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.6.1 (device 2026-11-25: the lake turned 20–30° left soon after it showed): placed from a steady second, not the first. */
class FacingTest {
    private fun run(f: Facing, seconds: Double, head: (Double) -> Double, start: Double = 0.0): Double {
        var t = start
        while (t < start + seconds) { t += 1 / 72.0; if (f.update(head(t), 1 / 72.0)) return t }
        return -1.0
    }

    @Test
    fun theFirstSecondAfterLaunchIsNotUsed() {
        val f = Facing()
        // Launch: the head reading swings 30° left for the first second, then the player looks straight ahead.
        val at = run(f, 6.0, { t -> if (t < 1.0) 30.0 else 0.4 * kotlin.math.sin(t * 3) })
        assertTrue(at > 0)
        assertTrue("placed at ${f.yaw}", kotlin.math.abs(f.yaw) < 2.0)
    }

    @Test
    fun itWaitsForASteadySecond() {
        val f = Facing()
        // Looking around for 3 s, then still at 10° right of the room's front.
        val at = run(f, 6.0, { t -> if (t < 3.0) 40 * kotlin.math.sin(t * 2) else -10.0 })
        assertTrue("placed at $at s", at >= 3.8)
        assertEquals(-10.0, f.yaw, 0.5)
    }

    @Test
    fun itGivesUpWaitingAfterFiveSeconds() {
        val f = Facing()
        assertTrue(run(f, 6.0, { t -> 20 * kotlin.math.sin(t * 5) }) in 4.9..5.2)
        assertTrue(f.placed)
        assertFalse(Facing().placed)
    }
}
