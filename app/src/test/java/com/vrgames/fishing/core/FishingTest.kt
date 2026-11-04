package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A careful angler meets every mission at every lake; the line breaks if one reels into a run. */
class FishingTest {
    /** Plays [lake] as a careful angler: right bait and spot for the missing fish, reel only while it is calm. */
    private fun angle(lake: Lake, maxCasts: Int = 40): Trip {
        val t = Trip(lake)
        var lastPower = 0.0
        var guard = 0
        while (!lake.missions.all { it.check(t) } && t.casts < maxCasts && guard++ < 200000) {
            when (t.state) {
                Trip.State.READY, Trip.State.LANDED -> {
                    val want = wanted(lake, t)
                    t.bait = want.likes.first()
                    t.aimYaw = if (want.rocks && lake.rocksAt.isNotEmpty()) lake.rocksAt[0].first else 0.0
                    t.press()
                }
                Trip.State.POWER -> {
                    val want = wanted(lake, t)
                    val goal = if (want.rocks && lake.rocksAt.isNotEmpty()) lake.rocksAt[0].second else if (want.far) 13.0 else 6.0
                    val p = (goal - 3.0) / 13.0
                    // Press as the swinging power passes the goal (it moves too fast to wait for an exact match).
                    if ((lastPower - p) * (t.power - p) <= 0 && t.power > lastPower) t.press()
                    lastPower = t.power
                }
                Trip.State.BITE -> t.press()
                Trip.State.FIGHT -> if (t.running <= 0 && t.tension < 0.7) t.reel(2.4 * Trip.STEP)
                Trip.State.NETTABLE -> t.net()
                else -> Unit
            }
            t.step(Trip.STEP)
        }
        println("${lake.id}: casts=${t.casts} breaks=${t.breaks} spooked=${t.spooked} catches=${t.catches.map { "${it.species}:${it.jin}" }} stars=${t.stars()} time=${t.time.toInt()}")
        return t
    }

    private fun wanted(lake: Lake, t: Trip): Species {
        val need = when (lake.id) {
            "xihu" -> if (t.count("crucian") < 3) "crucian" else "bream"
            "qiandao" -> if (!t.catches.any { it.species == "bighead" && it.jin >= 5 }) "bighead" else "culter"
            "lijiang" -> if (t.count("mandarin") < 2) "mandarin" else "catfish"
            "night" -> if (t.count("koi") < 2) "koi" else "crucian"
            "king" -> "king"
            else -> "bighead"
        }
        return Fish.byId(need)
    }

    @Test
    fun everyLakeCanBeFishedToThreeStars() {
        val bad = ArrayList<String>()
        for (l in Lakes.ALL) {
            val t = angle(l)
            if (t.stars() < 3) bad += "${l.id} stars=${t.stars()} breaks=${t.breaks}"
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    @Test
    fun reelingIntoARunBreaksTheLine() {
        val t = Trip(Lakes.byId("qiandao"))
        t.bait = Bait.DOUGH
        t.press(); t.step(0.35); t.press()
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 10000) t.step(Trip.STEP)
        t.press()
        assertEquals(Trip.State.FIGHT, t.state)
        guard = 0
        while (t.state == Trip.State.FIGHT && guard++ < 10000) { t.reel(4.0 * Trip.STEP); t.step(Trip.STEP) }
        assertEquals(1, t.breaks)
    }

    @Test
    fun strikingAtANibbleSpooksTheFish() {
        val t = Trip(Lakes.byId("xihu"))
        t.press(); t.step(0.3); t.press()
        var guard = 0
        while (t.nibbleT <= 0 && t.state == Trip.State.WAIT && guard++ < 10000) t.step(Trip.STEP)
        if (t.nibbleT > 0) {
            assertEquals("spooked", t.press())
            assertEquals(1, t.spooked)
        }
    }
}
