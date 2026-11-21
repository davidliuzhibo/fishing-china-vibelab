package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.5.1 (device test 2026-11-21 on 0.5.0): the bait (when it can be changed, what the left panel says about it, each
 * lake's starting bait) and the left panel's live values (a fight's tension, strength, distance redraw as they change).
 */
class V051Test {
    // ---- Bait ----

    @Test
    fun theBaitCanBeChangedOnlyWhileTheLineIsIn() {
        assertTrue(BaitRules.canChange(Trip.State.READY))
        assertTrue(BaitRules.canChange(Trip.State.LANDED))
        for (s in listOf(Trip.State.WAIT, Trip.State.BITE, Trip.State.FIGHT, Trip.State.NETTABLE)) {
            assertFalse("$s", BaitRules.canChange(s))
            assertTrue(BaitRules.lockedWhy(s)!!.startsWith("线已抛出"))
        }
        assertTrue(BaitRules.lockedWhy(Trip.State.WAIT)!!.contains("收竿重抛"))
        assertNull(BaitRules.lockedWhy(Trip.State.READY))
    }

    @Test
    fun theButtonIsGreyFromTheCastUntilTheFishIsLandedOrLost() {
        val t = Trip(Lakes.byId("xihu"))
        fun ok() = LiveHud.of(t, 0, 0.0, false, true).baitOk
        assertTrue("ready", ok())
        t.cast(0.4)
        assertEquals(Trip.State.WAIT, t.state)
        assertFalse("float out", ok())
        t.debugState(Trip.State.FIGHT)
        assertFalse("fighting", ok())
        t.debugState(Trip.State.NETTABLE)
        assertTrue(t.net())
        assertEquals(Trip.State.LANDED, t.state)
        assertTrue("landed", ok())
        // Winding in to cast again: the line is in, the bait can be changed.
        t.cast(0.4)
        assertFalse(ok())
        assertTrue(t.reelIn())
        assertTrue("wound in", ok())
        // A broken line: back to READY.
        t.debugState(Trip.State.FIGHT)
        repeat(200) { t.reel(3.0); t.step(0.05) }
        assertEquals(Trip.State.READY, t.state)
        assertTrue("after a break", ok())
        // The picker only shows while the bait can be changed, and nowhere off the lake (cards, results).
        assertTrue(LiveHud.of(t, 0, 0.0, true, true).picker)
        assertFalse(LiveHud.of(t, 0, 0.0, true, false).picker)
        t.cast(0.4)
        assertFalse(LiveHud.of(t, 0, 0.0, true, true).picker)
    }

    @Test
    fun everyLakeStartsOnABaitItsMainGoalFishTakes() {
        val want = mapOf("xihu" to Bait.WORM, "qiandao" to Bait.DOUGH, "lijiang" to Bait.WORM, "chagan" to Bait.DOUGH, "night" to Bait.CORN, "king" to Bait.DOUGH)
        for (l in Lakes.ALL) {
            assertEquals(l.id, want[l.id], l.startBait)
            assertEquals(l.startBait, Trip(l).bait)
            assertTrue(l.id, BaitRules.fansHere(l, l.startBait).isNotEmpty())
            l.missions.first().fish?.let { assertTrue(l.id, l.startBait in Fish.byId(it).likes) }
        }
    }

    @Test
    fun theLeftPanelHintsWhenTheBaitCannotCatchTheGoals() {
        val t = Trip(Lakes.byId("qiandao"))
        // Started on 面饵 (the 鳙鱼's): the 翘嘴 goal needs 亮片 — the device's example.
        assertEquals("目标里的翘嘴要用亮片", BaitRules.hint(t))
        t.bait = Bait.WORM
        assertEquals("这里的鱼不吃蚯蚓，换成玉米、面饵或亮片", BaitRules.hint(t))
        t.bait = Bait.SPOON
        assertEquals("目标里的鳙鱼要用面饵", BaitRules.hint(t))
        assertEquals("翘嘴（目标）爱吃", BaitRules.line(t, Bait.SPOON))
        assertEquals("这里的鱼都不吃", BaitRules.line(t, Bait.WORM))
        assertEquals("鳙鱼（目标）、白鲢爱吃", BaitRules.line(t, Bait.DOUGH))
        // 西湖 on worms: the 鳊鱼 goal wants 面饵 or 玉米.
        assertEquals("目标里的鳊鱼要用面饵或玉米", BaitRules.hint(Trip(Lakes.byId("xihu"))))
        // A goal met is not hinted at any more.
        val x = Trip(Lakes.byId("xihu"))
        x.bait = Bait.DOUGH
        assertNull(BaitRules.hint(x))
        // The ice: nothing takes worms there either.
        val ice = Trip(Lakes.byId("chagan"))
        ice.bait = Bait.WORM
        assertEquals("这里的鱼不吃蚯蚓，换成玉米或面饵", BaitRules.hint(ice))
    }

    @Test
    fun aBaitNothingHereTakesNeverBites() {
        // Why the hint matters (unchanged rule): at 千岛湖 on worms the float waits for ever.
        val t = Trip(Lakes.byId("qiandao"))
        t.bait = Bait.WORM
        t.cast(0.8)
        repeat(1200) { t.step(0.05) }
        assertEquals(Trip.State.WAIT, t.state)
        assertNull(t.fish)
    }

    // ---- The left panel's live values ----

    @Test
    fun aFightsNumbersChangeFromOneSnapshotToTheNext() {
        val t = Trip(Lakes.byId("qiandao"))
        t.debugState(Trip.State.FIGHT)
        val a = LiveHud.of(t, 7, 0.0, false, true)
        assertTrue(a.fighting)
        assertFalse(a.baitOk)
        // 0.2 s later (a frame or two at 10 snapshots a second), without reeling: the fish pulls, tires.
        t.step(0.2)
        val b = LiveHud.of(t, 7, 0.0, false, true)
        assertNotEquals("tension or strength moved in 0.2 s", a, b)
        // Reeling brings it in: the distance and the turns change.
        val d0 = b.distDm
        repeat(40) { t.reel(0.06); t.step(0.05) }
        val c = LiveHud.of(t, 7, 2.4, false, true)
        assertTrue("distance ${d0} -> ${c.distDm}", c.distDm < d0 || t.state != Trip.State.FIGHT)
        assertEquals(2, c.turns)
        // The same state twice: equal snapshots, so Compose redraws nothing.
        assertEquals(LiveHud.of(t, 7, 2.4, false, true), LiveHud.of(t, 7, 2.4, false, true))
    }

    @Test
    fun overAFightTheSnapshotChangesAtLeastFiveTimesASecond() {
        val t = Trip(Lakes.byId("qiandao"))
        t.debugState(Trip.State.FIGHT)
        var last = LiveHud.of(t, 0, 0.0, false, true)
        var changes = 0
        // 3 s of a fight at 10 snapshots a second, reeling gently.
        repeat(30) {
            repeat(2) { t.reel(0.04); t.step(0.05) }
            val now = LiveHud.of(t, 0, 0.0, false, true)
            if (now != last) changes++
            last = now
        }
        assertTrue("changes $changes in 3 s", changes >= 15)
    }

    // ---- Every panel reads a state that changes ----

    private val content = File("src/main/java/com/vrgames/fishing/content")

    /** A read of a Game property (not a call), e.g. `Game.trip`, `Game.live`. */
    private val read = Regex("Game\\.([a-z][A-Za-z]*)\\b(?!\\s*\\()")

    @Test
    fun everyPanelBoxReadsTheLiveStateFirst() {
        val text = File(content, "Panels.kt").readText()
        val lines = text.lines()
        var boxes = 0
        for ((k, line) in lines.withIndex()) {
            if (!line.contains("PanelBox(c, ") || !line.trimEnd().endsWith("{")) continue
            boxes++
            val first = lines.drop(k + 1).first { it.isNotBlank() && !it.trim().startsWith("//") }.trim()
            assertTrue("line ${k + 1}: a PanelBox's content must read Game.live first (Compose skips it otherwise), not: $first", first.contains("Game.live"))
        }
        assertTrue("checked $boxes panels", boxes >= 6)
    }

    @Test
    fun everyComposableReadingTheGameReadsAChangingStateFirst() {
        val bad = ArrayList<String>()
        var checked = 0
        for (f in content.listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "LakeStage.kt" }) {
            val text = f.readText()
            for (part in text.split("@Composable").drop(1).map { it.trimStart() }) {
                val name = Regex("fun\\s+(?:[A-Za-z.]+\\.)?([A-Za-z]+)\\(").find(part)?.groupValues?.get(1) ?: continue
                val body = part.split(Regex("\n(?=(?:/\\*\\*|private |fun |val |var |const |object |class |@))"))[0]
                val first = read.find(body) ?: continue
                checked++
                if (first.groupValues[1] != "live" && first.groupValues[1] != "version") bad += "${f.name}:$name reads Game.${first.groupValues[1]} first"
            }
        }
        assertTrue("composables reading the game without Game.live / Game.version first: $bad", bad.isEmpty())
        assertTrue("checked $checked composables", checked >= 12)
    }

    @Test
    fun theFightsNumbersComeFromTheLiveSnapshot() {
        val text = File(content, "Panels.kt").readText()
        // The left panel and its fight block show the outing's changing numbers from the snapshot, not the outing's fields.
        for (fn in listOf("fun HudPanel(", "fun FightBlock(")) {
            val body = text.substring(text.indexOf(fn)).split("\n@Composable")[0]
            for (raw in listOf("t.tension", "t.distance", "strengthLeft(", "t.running", "t.casts", "t.breaks", "t.state")) assertFalse("$fn reads $raw", body.contains(raw))
        }
        val game = File("src/main/java/com/vrgames/fishing/game/Game.kt").readText()
        // Every change goes through changed() (the counter and the snapshot together), and the frame renews the snapshot.
        assertEquals(1, Regex("version\\+\\+").findAll(game).count())
        assertTrue(game.contains("refreshLive()"))
    }
}
