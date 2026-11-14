package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13 device test: a fish a metre off could not be netted, and nothing said why. */
class NetTest {
    @Test
    fun aFishReeledInBecomesNettableBelowSixtyPercentStrength() {
        val t = Trip(Lakes.byId("xihu"))
        t.press(); t.step(0.35); t.press()
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        assertEquals(Trip.State.FIGHT, t.state)
        guard = 0
        // Reel gently (never into a run) until it can be netted.
        while (t.state == Trip.State.FIGHT && guard++ < 200000) {
            if (t.running <= 0 && t.tension < 0.6) t.reel(1.5 * Trip.STEP)
            t.step(Trip.STEP)
        }
        assertEquals(Trip.State.NETTABLE, t.state)
        assertTrue(t.distance <= Trip.NET_DIST)
        assertTrue(Trip.strengthLeft(t) < Trip.NET_TIRED)
        assertTrue(t.net())
        assertEquals(Trip.State.LANDED, t.state)
    }

    @Test
    fun theNetDoesNothingWhileTheFishIsStillFighting() {
        val t = Trip(Lakes.byId("xihu"))
        t.press(); t.step(0.35); t.press()
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        assertFalse(t.net())
        assertEquals(Trip.State.FIGHT, t.state)
    }
}
