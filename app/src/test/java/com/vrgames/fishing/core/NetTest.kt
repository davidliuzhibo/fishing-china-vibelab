package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13 device test: a fish a metre off could not be netted, and nothing said why. */
class NetTest {
    /** 2026-11-21 emulator test: 2 s frames poured all their winding into one 0.05 s sub-step and broke the line. */
    @Test
    fun theSameWindingGivesTheSameTensionWhateverTheFrameLength() {
        fun fightAt(frame: Double): Pair<Double, Trip.State> {
            val t = Trip(Lakes.byId("xihu"))
            assertEquals("cast", t.cast(0.4))
            var guard = 0
            while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
            t.strike()
            var maxTension = 0.0
            var s = 0.0
            // 0.8 turns a second, steady, for 6 s.
            while (s < 6.0 && (t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE)) {
                t.reel(0.8 * frame)
                t.step(frame)
                maxTension = maxOf(maxTension, t.tension)
                s += frame
            }
            return maxTension to t.state
        }
        val (fine, _) = fightAt(1.0 / 90)
        val (coarse, state) = fightAt(2.0)
        assertTrue("tension with 2 s frames $coarse vs $fine at 90 fps", coarse < fine + 0.25)
        assertTrue(state != Trip.State.READY)
    }

    /** 2026-11-21 emulator test: a fish at the bank made another run while the player reached for the net. */
    @Test
    fun aTiredFishAtTheBankStaysNettableWhileThePlayerTakesTheNet() {
        val t = Trip(Lakes.byId("xihu"))
        assertEquals("cast", t.cast(0.4))
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        guard = 0
        while (t.state == Trip.State.FIGHT && guard++ < 200000) {
            if (t.running <= 0 && t.tension < 0.6) t.reel(1.5 * Trip.STEP)
            t.step(Trip.STEP)
        }
        assertEquals(Trip.State.NETTABLE, t.state)
        // Hands off the reel for 20 s (taking up the net): no run, still in the net's reach.
        var s = 0.0
        while (s < 20.0) { t.step(Trip.STEP); s += Trip.STEP }
        assertEquals(Trip.State.NETTABLE, t.state)
        assertTrue(t.running <= 0)
        assertTrue(t.distance <= Trip.NET_DIST)
    }

    @Test
    fun aFishReeledInBecomesNettableBelowSixtyPercentStrength() {
        val t = Trip(Lakes.byId("xihu"))
        assertEquals("cast", t.cast(0.4))
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
        assertEquals("cast", t.cast(0.4))
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        assertFalse(t.net())
        assertEquals(Trip.State.FIGHT, t.state)
    }
}
