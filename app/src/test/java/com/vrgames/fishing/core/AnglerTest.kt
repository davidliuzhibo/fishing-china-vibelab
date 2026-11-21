package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole hand play (core.Angler) with a real [Trip], run by the same simulated hands the emulator uses
 * (core.SimHands: its own hand model, not the game's) and by hand-written motions. What [Rig.out] does with each
 * action mirrors game.Game (which needs Android to run).
 */
class AnglerTest {
    class Rig(lakeId: String = "xihu", scenario: String? = null, tutorial: Boolean = false) {
        val trip = Trip(Lakes.byId(lakeId))
        val tut = Tutorial().also { if (tutorial) it.start() }
        val steps = ArrayList<Int>()
        val log = ArrayList<String>()
        val casts = ArrayList<OverheadCast.Result.Cast>()
        val fails = ArrayList<String>()
        val others = ArrayList<String>()
        var rodTakes = 0; var netTakes = 0; var cocks = 0; var strikes = 0
        var turns = 0.0
        private var landings = 0
        private var flight = -1.0
        var t = 0.0; private set
        private var stamp = 1_000_000_000L

        private fun ev(e: Tutorial.Ev) { val b = tut.step; tut.on(e); if (tut.step != b) steps += tut.step }

        val angler = Angler(object : Angler.Out {
            override fun log(s: String) { log += s }
            override fun rodTaken(how: String) { rodTakes++; ev(Tutorial.Ev.ROD_TAKEN) }
            override fun cocked() { cocks++; ev(Tutorial.Ev.COCKED) }
            override fun cast(c: OverheadCast.Result.Cast) {
                casts += c
                if (tut.active && tut.step in Tutorial.EASY) trip.easyNext = true
                trip.aimYaw = c.yaw
                if (trip.cast(OverheadCast.powerOf(c.distance)) == "cast") { ev(Tutorial.Ev.CAST); flight = 1.0 }
            }
            override fun castFailed(why: String) { fails += why; ev(if (why == "lost") Tutorial.Ev.FAIL_LOST else Tutorial.Ev.FAIL_SLOW) }
            override fun rodBack(why: String) { if (why == "landed") ev(Tutorial.Ev.FLOAT_LANDED); if (why == "lost") ev(Tutorial.Ev.ROD_LOST) }
            override fun strike(how: String) { strikes++; when (trip.strike()) { "hooked" -> ev(Tutorial.Ev.HOOKED); "early" -> ev(Tutorial.Ev.EARLY) } }
            override fun reel(turns: Double, by: String) { this@Rig.turns += turns; trip.reel(turns) }
            override fun netTaken(how: String) { netTakes++ }
            override fun netBack(why: String) {}
            override fun netScooped(how: String) { if (trip.net()) ev(Tutorial.Ev.FISH_LANDED) }
            override fun pinchOther(what: String) { others += what }
        })
        val sim = scenario?.let { SimHands(it).also { s -> s.log = { m -> log += m } } }

        fun world(): Angler.World {
            val st = trip.state
            val waiting = st == Trip.State.WAIT || st == Trip.State.BITE
            val fighting = st == Trip.State.FIGHT || st == Trip.State.NETTABLE
            val pitch = if (waiting) Layout.WAIT_PITCH else if (fighting) Layout.FIGHT_PITCH else Layout.READY_PITCH
            val yaw = if (waiting || fighting) trip.castYaw.coerceIn(-40.0, 40.0) * 0.9 else 0.0
            val dir = Layout.dirOf(pitch, yaw)
            return Angler.World(st, Layout.gripTarget(Layout.restGrip(pitch, yaw), dir), Layout.NET_GRIP, Layout.fishForNet(trip.castYaw, trip.distance), landings, trip.lake.ice, trip.casts)
        }

        /** [seconds] at 90 Hz: the trip, the float's flight, then the hands (simulated, or [hands] at the time). */
        fun run(seconds: Double, until: () -> Boolean = { false }, hands: ((Double) -> Pair<HandReading?, HandReading?>)? = null) {
            val dt = 1 / 90.0
            val end = t + seconds
            while (t < end && !until()) {
                t += dt
                trip.events.clear()
                trip.step(dt)
                for (e in trip.events) Tutorial.ofTripEvent(e)?.let { ev(it) }
                if (flight >= 0) { flight -= dt; if (flight < 0) landings++ }
                val w = world()
                if (sim != null) {
                    val f = sim.next(dt, w, angler, trip)
                    angler.frame(t, f.right, f.left, f.stamp, t, w)
                } else {
                    val (r, l) = hands?.invoke(t) ?: (null to null)
                    stamp += 11_111_111L
                    angler.frame(t, r, l, if (r != null || l != null) stamp else null, t, w)
                }
            }
        }
    }

    /** A plain hand at [palm]: open ([curl] 0.095) or a fist (0.04). */
    private fun hand(palm: Vec3, curl: Double = 0.095, jitter: Double = 0.0) = HandReading(
        palm = palm + Vec3(jitter, 0.0, 0.0), wrist = palm + Vec3(0.0, 0.0, 0.045),
        indexTip = palm + Vec3(0.01, 0.0, -0.085), thumbTip = palm + Vec3(-0.02, 0.03, -0.05),
        middleTip = palm + Vec3(0.0, 0.0, -curl), ringTip = palm + Vec3(-0.015, 0.0, -curl * 0.95),
    )

    private val lapR = Vec3(0.2, Layout.EYE - 0.6, -0.35)
    private val lapL = Vec3(-0.2, Layout.EYE - 0.6, -0.35)

    /** A hand that rests at [from], reaches [to] in 0.6 s, then closes (0.15 s) and stays. Live jitter of 0.2 mm. */
    private fun reachAndClose(from: Vec3, to: Vec3, start: Double): (Double) -> HandReading {
        return { t ->
            val k = ((t - start) / 0.6).coerceIn(0.0, 1.0)
            val s = k * k * (3 - 2 * k)
            val curl = 0.095 - 0.055 * ((t - start - 0.6) / 0.15).coerceIn(0.0, 1.0)
            hand(from + (to - from) * s, curl, if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0)
        }
    }

    @Test
    fun aFistAtTheGripTakesTheRod() {
        val r = Rig(scenario = "fist")
        r.run(4.0)
        assertEquals(Angler.Rod.RIGHT, r.angler.rod)
        assertEquals(1, r.rodTakes)
        assertTrue(r.casts.isEmpty())
    }

    @Test
    fun theTwoStepCastCastsStraightAndTheRodGoesBack() {
        val r = Rig(scenario = "cast")
        r.run(9.0)
        val c = r.casts.single()
        assertTrue("distance ${c.distance}", c.distance in 8.0..16.0)
        assertTrue("yaw ${c.yaw}", abs(c.yaw) <= 5.0)
        assertEquals(1, r.cocks)
        assertEquals(Angler.Rod.HOLDER, r.angler.rod)
        assertTrue(r.trip.state == Trip.State.WAIT || r.trip.state == Trip.State.BITE)
    }

    @Test
    fun repeatedTrackerFramesCastTheSame() {
        val a = Rig(scenario = "cast").also { it.run(9.0) }.casts.single()
        val b = Rig(scenario = "cast60").also { it.run(9.0) }.casts.single()
        assertTrue("${a.distance} vs ${b.distance}", abs(a.distance - b.distance) < 2.5)
        assertTrue("${a.yaw} vs ${b.yaw}", abs(a.yaw - b.yaw) < 5)
    }

    @Test
    fun trackingFrozenMidThrowIsNoCast() {
        val r = Rig(scenario = "freeze")
        r.run(9.0)
        assertTrue(r.casts.toString(), r.casts.isEmpty())
        assertEquals(listOf("lost"), r.fails)
        assertEquals(Trip.State.READY, r.trip.state)
    }

    @Test
    fun heldStillCockedWithNoiseNeverCasts() {
        val r = Rig(scenario = "noisy")
        r.run(10.0)
        assertTrue(r.cocks >= 1)
        assertTrue(r.casts.toString(), r.casts.isEmpty())
        assertTrue(r.log.toString(), r.log.any { it.contains("held cocked and still 5 s") })
    }

    @Test
    fun aHandRestingByTheNetNeverTakesIt() {
        val r = Rig(scenario = "lapnet")
        r.trip.debugState(Trip.State.FIGHT)
        r.run(7.0)
        assertEquals(0, r.netTakes)
        r.trip.debugState(Trip.State.NETTABLE)
        r.run(7.0)
        assertEquals(0, r.netTakes)
        assertEquals(Angler.Net.REST, r.angler.net)
    }

    @Test
    fun theNetCannotBeTakenWhileTheFishFightsOnlyAtTheBank() {
        val r = Rig()
        r.trip.debugState(Trip.State.FIGHT)
        val reach = reachAndClose(lapL, Layout.NET_GRIP + Vec3(0.0, -0.02, 0.05), 0.5)
        r.run(2.0) { t -> hand(lapR, jitter = if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0) to reach(t) }
        // A gaze-and-pinch on the net with the left hand right there: nothing either.
        r.angler.pinch("net", (Layout.NET_GRIP + Vec3(0.0, -0.02, 0.05)))
        r.run(0.5) { t -> hand(lapR, jitter = if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0) to reach(t) }
        assertEquals(0, r.netTakes)
        assertTrue(r.others.contains("net-early"))
        // Tired at the bank: the same deliberate fist takes it.
        r.trip.debugState(Trip.State.NETTABLE)
        val start = r.t + 0.3
        val reach2 = reachAndClose(lapL, Layout.NET_GRIP + Vec3(0.0, -0.02, 0.05), start)
        r.run(2.0) { t -> hand(lapR, jitter = if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0) to reach2(if (t < start) start else t) }
        assertEquals(1, r.netTakes)
        assertEquals(Angler.Net.LEFT, r.angler.net)
    }

    @Test
    fun aGazePinchOnTheNetAtTheBankPutsItInTheLeftHand() {
        val r = Rig()
        r.trip.debugState(Trip.State.NETTABLE)
        val hands = { t: Double -> hand(lapR, jitter = if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0) to hand(lapL, jitter = if ((t * 90).toInt() % 2 == 1) 0.0002 else 0.0) }
        r.run(0.3, hands = hands)
        r.angler.pinch("net", lapL + Vec3(-0.02, 0.03, -0.05))
        r.run(0.1, hands = hands)
        assertEquals(Angler.Net.LEFT, r.angler.net)
    }

    @Test
    fun circlingInAnyPlaneWinds() {
        for (sc in listOf("reelface", "reelpedal", "reelsmall")) {
            val r = Rig(scenario = sc)
            r.trip.debugState(Trip.State.FIGHT)
            val d0 = r.trip.distance
            // The simulated angler stops while the fish runs or the line is near breaking (as one should).
            r.run(15.0, until = { r.trip.state == Trip.State.LANDED })
            assertTrue("$sc: ${r.turns} turns", r.turns > 4.0)
            assertTrue("$sc: ${r.trip.distance} of $d0 m, ${r.trip.state}", r.trip.state == Trip.State.LANDED || r.trip.distance < d0 - 3)
        }
    }

    @Test
    fun reelingAllTheWayLandsTheFishWithoutTheNet() {
        val r = Rig(scenario = "reelonly")
        r.trip.debugState(Trip.State.FIGHT)
        r.run(40.0, until = { r.trip.state == Trip.State.LANDED })
        assertEquals(Trip.State.LANDED, r.trip.state)
        assertEquals("reel", r.trip.landedBy)
        assertEquals(0, r.netTakes)
        assertEquals(0, r.trip.breaks)
    }

    @Test
    fun aGazePinchOnTheGripPutsTheRodInThePinchingHand() {
        val r = Rig()
        val hands = { t: Double -> hand(lapR, jitter = if ((t * 90).toInt() % 2 == 0) 0.0002 else 0.0) to hand(lapL, jitter = if ((t * 90).toInt() % 2 == 1) 0.0002 else 0.0) }
        r.run(0.3, hands = hands)
        // The left hand pinched (the event's position is at its thumb and index).
        r.angler.pinch("rod", lapL + Vec3(-0.005, 0.03, -0.065))
        assertEquals(Angler.Rod.LEFT, r.angler.rod)
        assertTrue(r.log.any { it.contains("nearest thumb-index") })
    }

    @Test
    fun withNoLiveHandsTheRodIsHeldByTheDragAndThrownTheSameWay() {
        val r = Rig()
        r.run(0.2)
        r.angler.pinch("rod", null)
        assertEquals(Angler.Rod.DRAG, r.angler.rod)
        // Raised 20 cm and drawn back 12 cm, held, then thrown forward 35 cm in 0.28 s.
        r.angler.dragStart("rod", r.t)
        var p = Vec3.ZERO
        var tt = r.t
        fun to(q: Vec3, seconds: Double, n: Int) {
            val a = p
            for (k in 1..n) { val u = k.toDouble() / n; val s = u * u * u * (10 - 15 * u + 6 * u * u); val q2 = a + (q - a) * s; tt += seconds / n; r.angler.dragMove(q2 - p, tt); p = q2 }
        }
        to(Vec3(0.0, 0.2, 0.12), 0.5, 40)
        to(Vec3(0.0, 0.2, 0.121), 0.2, 10)
        assertEquals(1, r.cocks)
        to(Vec3(-0.04, -0.02, -0.23), 0.28, 28)
        to(Vec3(-0.04, -0.02, -0.231), 0.15, 8)
        r.angler.dragEnd()
        r.run(0.2)
        val c = r.casts.single()
        assertTrue("distance ${c.distance}", c.distance > 8)
    }

    @Test
    fun aPinchOnTheWaterBeforeTheRodIsTakenCastsNothing() {
        // 0.4.0: with no live hands a pinch on the water started the old power swing, a second one cast toward the head.
        val r = Rig()
        r.run(5.0)
        repeat(3) { r.angler.pinch("water", null); r.run(0.5) }
        assertEquals(Trip.State.READY, r.trip.state)
        assertTrue(r.casts.isEmpty())
        assertEquals(listOf("water-ready", "water-ready", "water-ready"), r.others)
    }

    @Test
    fun aPinchWhileTheFloatIsDownStrikes() {
        val r = Rig()
        r.trip.debugState(Trip.State.BITE)
        r.run(0.1)
        r.angler.pinch("bait", null)
        assertEquals(Trip.State.FIGHT, r.trip.state)
    }

    @Test
    fun theTutorialRunsFromTakingTheRodToTheCatch() {
        val r = Rig(scenario = "tutorial", tutorial = true)
        // v0.5.1: step 1 is the bait, passed with the left panel's 「换饵」 (or 「知道了」); the hands do the rest.
        r.tut.on(Tutorial.Ev.BAIT)
        assertEquals(2, r.tut.step)
        r.run(120.0, until = { r.tut.step == Tutorial.TOTAL + 1 })
        assertEquals(r.log.joinToString("\n"), Tutorial.TOTAL + 1, r.tut.step)
        // Each step reached in order (setbacks may repeat one, never skip one).
        val firsts = (3..Tutorial.TOTAL + 1).map { s -> r.steps.indexOf(s) }
        assertTrue("steps ${r.steps}", firsts.all { it >= 0 } && firsts == firsts.sorted())
        assertEquals(1, r.trip.catches.size)
        assertEquals("crucian", r.trip.catches.single().species)
        assertEquals("net", r.trip.landedBy)
        assertEquals(1, r.netTakes)
    }
}
