package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5.2 (device test 2026-11-21): the left panel says how far each goal has got, e.g. 「钓到 3 条鲫鱼（1/3）」. */
class GoalProgressTest {
    private fun landOne(t: Trip) {
        assertEquals("cast", t.cast(0.4))
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        guard = 0
        while ((t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE) && guard++ < 400000) {
            if (t.running <= 0 && t.tension < 0.6) t.reel(1.5 * Trip.STEP)
            t.step(Trip.STEP)
        }
    }

    @Test
    fun everyGoalShowsItsProgress() {
        for (lake in Lakes.ALL) {
            val t = Trip(lake)
            for (m in lake.missions) assertTrue("${lake.id}: ${m.text}", m.progress(t).startsWith("（"))
        }
    }

    @Test
    fun theCountGoesUpWithEachCatchAndTheSnapshotChanges() {
        val t = Trip(Lakes.byId("xihu"))
        val goal = t.lake.missions.first()
        assertEquals("（0/3）", goal.progress(t))
        val before = LiveHud.of(t, 0, 0.0, false, true)
        landOne(t)
        assertEquals(Trip.State.LANDED, t.state)
        val n = t.count("crucian")
        val after = LiveHud.of(t, 0, 0.0, false, true)
        assertEquals("（${minOf(n, 3)}/3）", after.progress.first())
        assertTrue(after != before)
        assertEquals("（到现在没断过）", t.lake.missions[2].progress(t))
    }

    @Test
    fun aBigFishGoalSaysHowBigTheBestWas() {
        val t = Trip(Lakes.byId("qiandao"))
        assertEquals("（0/1）", t.lake.missions.first().progress(t))
    }
}
