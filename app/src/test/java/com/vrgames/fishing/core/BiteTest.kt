package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13 device test: the float went down and the fish was gone before anyone could react. */
class BiteTest {
    private fun toBite(t: Trip) {
        t.press(); t.step(0.35); t.press()
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        assertEquals(Trip.State.BITE, t.state)
    }

    @Test
    fun aBiteHoldsForTenSecondsAndAStrikeLateInItStillHooks() {
        val t = Trip(Lakes.byId("xihu"))
        toBite(t)
        var s = 0.0
        while (s < 8.0) { t.step(Trip.STEP); s += Trip.STEP }
        assertEquals(Trip.State.BITE, t.state)
        t.strike()
        assertEquals(Trip.State.FIGHT, t.state)
    }

    @Test
    fun theWaitingPlayerIsRemindedAndOnlyThenTheFishLeaves() {
        val t = Trip(Lakes.byId("xihu"))
        toBite(t)
        t.events.clear()
        var s = 0.0
        while (t.state == Trip.State.BITE && s < 20) { t.step(Trip.STEP); s += Trip.STEP }
        assertTrue("left after ${s}s", s >= Trip.BITE_WINDOW - 0.05)
        assertTrue(t.events.count { it == "biting" } >= 2)
        assertTrue("missed" in t.events)
    }
}
