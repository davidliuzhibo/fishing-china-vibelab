package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A careful angler meets every mission at every lake; the line breaks if one reels into a run. */
class FishingTest {
    /** Plays [lake] as a careful angler: right bait and spot for the missing fish, reel only while it is calm. */
    private fun angle(lake: Lake, maxCasts: Int = 40): Trip {
        val t = Trip(lake)
        var guard = 0
        while (!lake.missions.all { it.check(t) } && t.casts < maxCasts && guard++ < 200000) {
            when (t.state) {
                Trip.State.READY, Trip.State.LANDED -> {
                    val want = wanted(lake, t)
                    t.bait = want.likes.first()
                    t.aimYaw = if (want.rocks && lake.rocksAt.isNotEmpty()) lake.rocksAt[0].first else 0.0
                    val goal = if (want.rocks && lake.rocksAt.isNotEmpty()) lake.rocksAt[0].second else if (want.far) 13.0 else 6.0
                    // The arm's throw (v0.5: no power swing); on the ice a pinch drops the line into the hole.
                    if (lake.ice) t.press() else t.cast((goal - 3.0) / 13.0)
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
        t.cast(0.8)
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
        t.cast(0.3)
        var guard = 0
        while (t.nibbleT <= 0 && t.state == Trip.State.WAIT && guard++ < 10000) t.step(Trip.STEP)
        if (t.nibbleT > 0) {
            assertEquals("spooked", t.press())
            assertEquals(1, t.spooked)
        }
    }

    /** v0.5: the net is optional — reeling a tired fish on up the bank lands it. */
    @Test
    fun reelingOnLandsTheFishWithoutTheNet() {
        val t = Trip(Lakes.byId("xihu"))
        t.cast(0.4)
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        assertEquals(Trip.State.FIGHT, t.state)
        guard = 0
        var sawNettable = false
        while (t.state != Trip.State.LANDED && t.state != Trip.State.READY && guard++ < 200000) {
            if (t.running <= 0 && t.tension < 0.6) t.reel(1.5 * Trip.STEP)
            t.events.clear()
            t.step(Trip.STEP)
            if (t.state == Trip.State.NETTABLE) sawNettable = true
        }
        assertTrue(sawNettable)
        assertEquals(Trip.State.LANDED, t.state)
        assertEquals("reel", t.landedBy)
        assertEquals(1, t.catches.size)
        assertEquals(0, t.breaks)
    }

    /** A fish with strength left stays out of the net's reach however hard one reels gently. */
    @Test
    fun aStrongFishIsNotHauledToTheBank() {
        val t = Trip(Lakes.byId("qiandao"))
        t.bait = Bait.DOUGH
        t.cast(0.8)
        var guard = 0
        while (t.state != Trip.State.BITE && guard++ < 100000) t.step(Trip.STEP)
        t.strike()
        var minFight = 99.0
        guard = 0
        while (t.state == Trip.State.FIGHT && guard++ < 400) {
            if (t.running <= 0 && t.tension < 0.6) t.reel(1.5 * Trip.STEP)
            t.step(Trip.STEP)
            if (t.state == Trip.State.FIGHT) minFight = minOf(minFight, t.distance)
        }
        assertTrue("came to $minFight m while fighting", minFight >= Trip.FIGHT_MIN_DIST - 1e-9)
    }

    /** The tutorial's fish: a crucian, a quick bite after one nibble, an early strike only told off, no runs. */
    @Test
    fun theTutorialFishIsEasy() {
        val t = Trip(Lakes.byId("qiandao"))
        t.easyNext = true
        assertEquals("cast", t.cast(0.9))
        assertTrue(t.easy)
        assertEquals("crucian", t.fish?.id)
        var s = 0.0
        while (t.nibbleT <= 0 && t.state == Trip.State.WAIT && s < 10) { t.step(Trip.STEP); s += Trip.STEP }
        assertTrue("nibble after $s s", t.nibbleT > 0 && s <= Trip.EASY_WAIT + 1.1)
        assertEquals("early", t.press())
        assertEquals(0, t.spooked)
        while (t.state == Trip.State.WAIT && s < 15) { t.step(Trip.STEP); s += Trip.STEP }
        assertEquals(Trip.State.BITE, t.state)
        assertTrue("bite after $s s", s < 5.0)
        t.strike()
        var runs = 0
        var guard = 0
        while (t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE) {
            if (guard++ > 20000) break
            t.events.clear()
            t.reel(1.0 * Trip.STEP)
            t.step(Trip.STEP)
            runs += t.events.count { it == "run" }
        }
        assertEquals(0, runs)
        assertEquals(Trip.State.LANDED, t.state)
        assertFalse(t.easy)
    }

    /** A cast where no fish comes is no dead end: wind in and cast again. */
    @Test
    fun aCastCanBeWoundInToCastAgain() {
        val t = Trip(Lakes.byId("lijiang"))
        t.bait = Bait.CORN
        t.aimYaw = 0.0
        t.cast(0.1)
        assertEquals(Trip.State.WAIT, t.state)
        assertTrue(t.reelIn())
        assertEquals(Trip.State.READY, t.state)
        assertEquals("cast", t.cast(0.2))
    }
}
