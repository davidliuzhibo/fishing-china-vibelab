package com.vrgames.fishing.core

import kotlin.math.max

/**
 * v0.5: everything the hands (and the system's gaze-and-pinch) do at the water, as one pure state machine, so the
 * server tests run the same logic the headset does. The stage feeds it hand readings each frame ([frame]) and the
 * system's pinch and drag events ([pinch], [dragStart], [dragMove], [dragEnd]); it answers through [Out].
 *
 * The rod: in its holder, or in a hand — taken by a gaze-and-pinch on its glowing grip (into the pinching hand) or by a
 * deliberate fist at the grip ([Grab]); with no live hand tracking, a gaze-and-pinch holds it and the system drag moves
 * it ([Rod.DRAG]). Held, it is cast by the two-step throw ([OverheadCast], palm positions only) and drawn from that
 * state (never from the hand's orientation, so never behind the head). When the float lands it goes back to its holder.
 *
 * The bite: any gaze-and-pinch, or a fresh hand near the grip rising quickly ([StrikeLift]).
 * The fight: any free hand circling in front winds ([CircleReel]); a gaze-pinch-hold on the reel (or rod) with a
 * circling drag winds too.
 * The net (optional): usable only while the fish is tired at the bank (NETTABLE) — before that it is a ghost with no
 * collider and nothing takes it; then a gaze-and-pinch on it or a deliberate fist takes it; it goes back when the fish
 * is landed or swims off, on 「放下抄网」 ([dropNet]), or when its hand is gone [NET_LOST_S].
 */
class Angler(private val out: Out) {
    interface Out {
        fun log(s: String)
        fun rodTaken(how: String)
        fun cocked()
        fun cast(c: OverheadCast.Result.Cast)
        fun castFailed(why: String)
        fun rodBack(why: String)
        /** v0.6.0: the float landed while the hand still grips the rod: it stays in the hand. */
        fun rodKept() {}
        fun strike(how: String)
        fun reel(turns: Double, by: String)
        fun netTaken(how: String)
        fun netBack(why: String)
        fun netScooped(how: String)
        /** A pinch the game answers itself: "bait", "water-ready", "ice", "net-early", "reel-hint". */
        fun pinchOther(what: String)
    }

    /** What the angler sees this frame (from the view, or a test). */
    data class World(
        val state: Trip.State,
        /** Where a hand closes on the rod in its holder now (the cork's middle). */
        val rodGrip: Vec3,
        val netGrip: Vec3,
        /** The tired fish at the bank, for the net. */
        val fish: Vec3,
        /** Counts the float's landings (the rod goes back in its holder on the next one). */
        val landings: Int,
        val ice: Boolean,
        /** The trip's cast count (a cast the trip took shows here; the game may take it a frame later). */
        val casts: Int = 0,
        /**
         * v0.6.0: where the player's body is in the frame the scene is seen in (minus core.RenderOffset in the seat's
         * frame; zero for the tests' and the simulated hands): the shoulders the net hangs from are this far off.
         */
        val body: Vec3 = Vec3.ZERO,
    )

    enum class Rod { HOLDER, LEFT, RIGHT, DRAG }
    enum class Net { REST, LEFT, RIGHT, AUTO }

    val right = HandTrack("right")
    val left = HandTrack("left")
    private val hands = listOf(right, left)
    private val stamps = StampClock()
    var rod = Rod.HOLDER; private set
    var net = Net.REST; private set
    private val castHand = OverheadCast()
    private val castDrag = OverheadCast()
    private val reelPalm = mapOf(right to CircleReel(), left to CircleReel())
    private val reelTip = mapOf(right to CircleReel(), left to CircleReel())
    private val reelDrag = CircleReel()
    private val scoop = NetScoop()
    private val rodGrab = Grab()
    private val netGrab = Grab()
    var now = 0.0; private set
    private var lastNow = 0.0
    private var w: World? = null
    private var flying = false
    private var castAt = 0.0
    private var castAtUp = 0.0
    private var castsBefore = 0
    private var seenLandings = 0
    private var releaseDir = Layout.dirOf(35.0, 0.0)
    private var strikeCool = 0.0
    private var lastLive = Double.NEGATIVE_INFINITY
    private var dragOn = false
    private var crankOn = false
    private var dragPalm = Vec3.ZERO
    private var lastDragT = Double.NEGATIVE_INFINITY
    private var dragClock = 0.0
    private var autoT = -1.0
    private var autoReach = Vec3.ZERO
    private var landedT = -1.0
    private var dropAsked = false
    private var lastReelAt = Double.NEGATIVE_INFINITY

    /** Some fresh hand within [LIVE_S]. */
    var handsLive = false; private set
    /**
     * v0.6.0 (PICO 1125: 「至少手在视野里时需要钓竿跟手」; user: 握着就一直在手里，张开手才回竿架): the rod stays in the
     * hand after the cast while that hand still grips it, through the wait and the fight; then it points where the view
     * aims it (at the float or the fish, [rodAims]). It goes back to its holder when the hand opens ([OPEN_S] of open
     * readings) or is gone [ROD_LOST_S].
     */
    var kept = false; private set
    /** The rod is in a hand and the view aims it at the float or the fish (its direction from [rodPose] is a placeholder). */
    val rodAims get() = kept && rod != Rod.HOLDER
    private var openSince = Double.NaN
    /** The rod in a hand (or the drag): its grip and direction; null in the holder. */
    var rodPose: Pair<Vec3, Vec3>? = null; private set
    /** The net in a hand (or scooping by itself): the hand and the hoop; null on its rest. */
    var netPose: Pair<Vec3, Vec3>? = null; private set
    /** Radians the reel's crank has turned (from any winding), for the crank to show. */
    var crankSpin = 0.0; private set
    /** Seconds since the last winding (for the tutorial's extra help). */
    fun sinceReel() = now - lastReelAt
    val castPhase get() = (if (rod == Rod.DRAG) castDrag else castHand).phase
    val castDrawn get() = (if (rod == Rod.DRAG) castDrag else castHand).drawn

    fun holdsRod(h: HandTrack) = (rod == Rod.RIGHT && h === right) || (rod == Rod.LEFT && h === left)
    fun holdsNet(h: HandTrack) = (net == Net.RIGHT && h === right) || (net == Net.LEFT && h === left)
    private fun trackOf(r: Rod) = if (r == Rod.LEFT) left else right
    private fun trackOf(n: Net) = if (n == Net.LEFT) left else right

    // ---- Each frame ----

    /**
     * One frame (or one sub-step of the test's hands) at [time] (s): the hands' readings ([r], [l]; null: not tracked),
     * the tracker's [stamp] (null: none), the system clock [uptime] (s, for the drag's events) and the [world].
     */
    fun frame(time: Double, r: HandReading?, l: HandReading?, stamp: Long?, uptime: Double, world: World) {
        lastNow = now
        now = time
        dragClock = uptime
        w = world
        val stampNew = stamps.isNew(stamp)
        val st = stamps.time(time, stamp)
        right.update(time, st, r, stampNew)
        left.update(time, st, l, stampNew)
        if (right.fresh || left.fresh) lastLive = time
        handsLive = time - lastLive < LIVE_S
        stampLog(stamp, stampNew)
        rodFrame(world)
        reelFrame(world)
        netFrame(world)
    }

    private fun readyish(s: Trip.State) = s == Trip.State.READY || s == Trip.State.LANDED

    private fun rodFrame(w: World) {
        val ready = readyish(w.state)
        val waiting = w.state == Trip.State.WAIT || w.state == Trip.State.BITE
        when (rod) {
            Rod.HOLDER -> {
                rodPose = null
                if (ready && !flying) for (h in hands) {
                    if (!holdsNet(h) && rodGrab.update(h, w.rodGrip)) { takeRod(if (h === right) Rod.RIGHT else Rod.LEFT, h.palm!!, "fist ${h.name}, ${cm(h.palm!!.distanceTo(w.rodGrip))} cm from the grip, came ${cm(h.approach(w.rodGrip))} cm nearer"); break }
                }
                // v0.6.0: a fist round the grip while waiting or fighting takes the rod up again (kept in the hand).
                val fighting = w.state == Trip.State.FIGHT || w.state == Trip.State.NETTABLE
                if ((waiting || fighting) && !flying) for (h in hands) {
                    if (!holdsNet(h) && !reelEngaged(h) && rodGrab.update(h, w.rodGrip)) {
                        takeRod(if (h === right) Rod.RIGHT else Rod.LEFT, h.palm!!, "fist ${h.name} while ${w.state}, ${cm(h.palm!!.distanceTo(w.rodGrip))} cm from the grip")
                        kept = true; openSince = Double.NaN
                        return
                    }
                }
                if (waiting && now >= strikeCool) for (h in hands) {
                    if (!holdsNet(h) && StrikeLift.check(h, w.rodGrip, dwell = w.state == Trip.State.WAIT)) { strikeCool = now + 1.0; out.strike("lift ${h.name}"); break }
                }
            }
            Rod.LEFT, Rod.RIGHT, Rod.DRAG -> {
                if (flying) {
                    rodPose?.let { (g, _) -> rodPose = (if (rod == Rod.DRAG) g else trackOf(rod).palm ?: g) to releaseDir }
                    when {
                        w.landings != seenLandings -> landed()
                        // Refused (the trip never took it): judged on the system clock too, as the game takes the cast on
                        // its own thread a frame later (the emulator's frames are a second apart).
                        ready && w.casts == castsBefore && now - castAt > 1.5 && dragClock - castAtUp > 2.0 -> { flying = false; out.log("cast refused by the trip; the rod stays in the hand") }
                        now - castAt > 4.0 -> landed()
                    }
                    return
                }
                if (kept) { keptFrame(w, ready); return }
                if (!ready) { putBack("state ${w.state}"); return }
                if (rod == Rod.DRAG) {
                    handle(castDrag.gap(dragClock - lastDragT, drag = true))
                    if (rod == Rod.DRAG && !flying && !dragOn && dragClock - lastDragT > DRAG_IDLE_S) putBack("drag idle")
                    if (rod == Rod.DRAG && !flying) rodPose = dragPalm to rodDir(castDrag)
                    return
                }
                val h = trackOf(rod)
                if (h.isNew && h.fresh) handle(castHand.sample(h.t, h.palm!!))
                else handle(castHand.gap(h.lostFor(now), drag = false))
                if (flying) return
                if (h.lostFor(now) > ROD_LOST_S) { putBack("lost"); return }
                h.palm?.let { rodPose = it to rodDir(castHand) }
            }
        }
    }

    /** The float is on the water: the rod stays in a hand that still grips it, else goes back to its holder. */
    private fun landed() {
        flying = false
        val h = if (rod == Rod.DRAG) null else trackOf(rod)
        if (h != null && h.fresh && h.gripping) {
            kept = true
            openSince = Double.NaN
            h.palm?.let { rodPose = it to releaseDir }
            out.log("float landed: the rod stays in the ${h.name} hand (curl ${h.reading?.curl?.let { cm(it) } ?: "?"} cm)")
            out.rodKept()
        } else putBack("landed")
    }

    /** The rod kept in a hand after the cast: follows it; a lift strikes; the hand opening puts it back. */
    private fun keptFrame(w: World, ready: Boolean) {
        val h = trackOf(rod)
        if (h.lostFor(now) > ROD_LOST_S) { putBack("lost"); return }
        val r = h.reading
        if (h.isNew && h.fresh && r != null) {
            if (h.open) { if (openSince.isNaN()) openSince = now } else openSince = Double.NaN
            if (!openSince.isNaN() && now - openSince >= OPEN_S) { putBack("opened"); return }
        }
        h.palm?.let { rodPose = it to (rodPose?.second ?: releaseDir) }
        if (ready) {
            // The fish is in (or off): the rod is back to being cast from this hand.
            kept = false
            h.palm?.let { castHand.begin(it) }
            out.log("rod still in the ${h.name} hand, ready to cast again")
            return
        }
        if ((w.state == Trip.State.WAIT || w.state == Trip.State.BITE) && now >= strikeCool) {
            val p = h.palm
            if (p != null && StrikeLift.check(h, p)) { strikeCool = now + 1.0; out.strike("lift with the rod in the ${h.name} hand") }
        }
    }

    /** The rod drawn from the cast's state: 35° up and out when held, back to 95° (up, a little back) at the cock, forward again in the throw. */
    private fun rodDir(c: OverheadCast): Vec3 = when (c.phase) {
        OverheadCast.Phase.HELD -> Layout.dirOf(35.0 + 60.0 * c.drawn, 0.0)
        OverheadCast.Phase.COCKED -> Layout.dirOf(95.0 - 60.0 * c.thrown, c.aimYaw * c.thrown)
    }

    private fun handle(r: OverheadCast.Result?) {
        when (r) {
            null -> Unit
            OverheadCast.Result.Cocked -> { out.log("cocked (${rod.name.lowercase()})"); out.cocked() }
            is OverheadCast.Result.Cast -> {
                flying = true
                castAt = now
                castAtUp = dragClock
                castsBefore = w?.casts ?: 0
                seenLandings = w?.landings ?: 0
                releaseDir = Layout.dirOf(40.0, r.yaw)
                out.log("cast (${rod.name.lowercase()}): peak %.2f m/s, travel %.0f cm -> %.1f m, yaw %.0f°".format(r.peak, r.travel * 100, r.distance, r.yaw))
                out.cast(r)
            }
            is OverheadCast.Result.Fail -> { out.log("cast failed: ${r.why} (${rod.name.lowercase()})"); out.castFailed(r.why) }
        }
    }

    private fun takeRod(by: Rod, palm: Vec3, how: String) {
        rod = by
        flying = false
        if (by == Rod.DRAG) { castDrag.begin(palm); dragPalm = palm } else castHand.begin(palm)
        rodPose = palm to Layout.dirOf(35.0, 0.0)
        out.log("rod taken: $how")
        out.rodTaken(how)
    }

    private fun putBack(why: String) {
        if (rod == Rod.HOLDER) return
        rod = Rod.HOLDER
        flying = false
        kept = false
        openSince = Double.NaN
        rodPose = null
        out.log("rod back in the holder ($why)")
        out.rodBack(why)
    }

    // ---- The reel ----

    private fun reelFrame(w: World) {
        val fighting = w.state == Trip.State.FIGHT || w.state == Trip.State.NETTABLE
        var best = 0.0
        var by = ""
        for (h in hands) {
            val rp = reelPalm.getValue(h); val rt = reelTip.getValue(h)
            if (!fighting || holdsRod(h) || holdsNet(h)) { rp.reset(); rt.reset(); continue }
            val r = h.reading
            if (h.isNew && h.fresh && r != null) {
                val a = rp.update(h.t, r.palm)
                val b = r.indexTip?.let { rt.update(h.t, it) } ?: 0.0
                val turns = max(a, b)
                if (turns > best) { best = turns; by = "${h.name} hand, r ${cm(if (a >= b) rp.radius else rt.radius)} cm" }
            } else { rp.idle(h.t + h.lostFor(now)); rt.idle(h.t + h.lostFor(now)) }
        }
        if (!fighting) { reelDrag.reset(); crankOn = false }
        // A gaze-pinch-hold crank is winding: the hand's own circles are the same motion, not counted twice.
        if (crankOn) return
        if (best > 0) wound(best, by)
    }

    private fun wound(turns: Double, by: String) {
        crankSpin += turns * 2 * Math.PI
        lastReelAt = now
        out.reel(turns, by)
    }

    fun reelEngaged(h: HandTrack) = reelPalm.getValue(h).engaged || reelTip.getValue(h).engaged

    // ---- The net ----

    private fun netFrame(w: World) {
        val nettable = w.state == Trip.State.NETTABLE
        val dt = (now - lastNow).coerceIn(0.0, 0.5)
        if (dropAsked) { dropAsked = false; netBack("button") }
        when (net) {
            Net.REST -> {
                netPose = null
                if (nettable && landedT < 0) for (h in hands) {
                    if (!holdsRod(h) && !reelEngaged(h) && netGrab.update(h, w.netGrip)) { takeNet(if (h === right) Net.RIGHT else Net.LEFT, "fist ${h.name}, ${cm(h.palm!!.distanceTo(w.netGrip))} cm from the grip"); break }
                }
            }
            Net.LEFT, Net.RIGHT -> {
                val h = trackOf(net)
                when {
                    landedT < 0 && !nettable -> netBack(if (w.state == Trip.State.LANDED) "landed" else "the fish swam off (${w.state})")
                    h.lostFor(now) > NET_LOST_S -> netBack("hand lost")
                    else -> h.palm?.let { palm ->
                        val hoop = NetScoop.Pose.hoop(palm, (if (net == Net.LEFT) Layout.SHOULDER_L else Layout.SHOULDER_R) + w.body)
                        netPose = palm to hoop
                        if (nettable && landedT < 0 && h.isNew && scoop.update(h.t, hoop, w.fish)) scooped("hand", hoop)
                    }
                }
            }
            Net.AUTO -> {
                // No live hand: the net scoops by itself (out under the fish, then up through it).
                autoT += dt
                if (autoT < dt * 1.5 || autoReach == Vec3.ZERO) autoReach = NetScoop.Pose.handFor(w.fish + Vec3(0.0, -0.2, 0.0), Layout.SHOULDER_L + w.body, w.netGrip)
                val hand = when {
                    autoT < 0.5 -> w.netGrip * (1 - autoT / 0.5) + autoReach * (autoT / 0.5)
                    autoT < 0.7 -> autoReach
                    else -> autoReach + Vec3(0.0, 0.25 * ((autoT - 0.7) / 0.4).coerceAtMost(1.0), 0.0)
                }
                val hoop = NetScoop.Pose.hoop(hand, Layout.SHOULDER_L + w.body)
                netPose = hand to hoop
                if (landedT < 0 && nettable && (scoop.update(now, hoop, w.fish) || autoT > 1.1)) scooped("by itself", hoop)
                if (landedT < 0 && !nettable) netBack("the fish swam off (${w.state})")
                else if (autoT > 2.5 && landedT < 0) netBack("auto ended")
            }
        }
        // After a landing the net is put back by itself.
        if (landedT >= 0) { landedT += dt; if (landedT > 1.2) { landedT = -1.0; netBack("landed") } }
    }

    private fun takeNet(by: Net, how: String) {
        net = by
        scoop.reset()
        autoT = if (by == Net.AUTO) 0.0 else -1.0
        autoReach = Vec3.ZERO
        out.log("net taken: $how")
        out.netTaken(how)
    }

    private fun netBack(why: String) {
        if (net == Net.REST) return
        net = Net.REST
        autoT = -1.0
        netPose = null
        out.log("net back on its rest ($why)")
        out.netBack(why)
    }

    private fun scooped(how: String, hoop: Vec3) {
        landedT = 0.0
        out.log("net scooped the fish ($how), hoop at %.2f, %.2f, %.2f".format(hoop.x, hoop.y, hoop.z))
        out.netScooped(how)
    }

    /** 「放下抄网」: the net back on its rest at the next frame. */
    fun dropNet() { dropAsked = true }

    /** Not fishing now (the cards, the result): pinches do nothing until the next [frame]. */
    fun pause() { w = null }

    /** The crank turned by other means (the HUD's 「收线」): shown on the reel. */
    fun addCrank(turns: Double) { crankSpin += turns * 2 * Math.PI }

    // ---- The system's gaze-and-pinch ----

    /**
     * A system pinch-down on [target] ("rod", "reel", "bait", "net", "water", or null: nothing of ours) with the
     * pinching hand's position [at] in the seat's frame (null: the event had none).
     */
    fun pinch(target: String?, at: Vec3?) {
        val w = this.w ?: return
        val (who, rule) = whichHand(at)
        val near = who?.reading?.pinchPoint
        out.log("pinch on ${target ?: "-"} in ${w.state}: event at ${fmt(at)}, hand=${who?.name ?: "none"} ($rule)" +
            (if (at != null && near != null) ", event − tracked thumb-index = ${fmt(at - near)} (${cm(at.distanceTo(near))} cm)" else "") +
            ", fresh R=${right.fresh} L=${left.fresh}")
        val ready = readyish(w.state)
        when {
            w.state == Trip.State.BITE -> out.strike("pinch on ${target ?: "-"}")
            target == "bait" -> out.pinchOther("bait")
            target == "net" -> pinchNet(w, who)
            w.state == Trip.State.WAIT -> if (target == "water" || target == "rod" || target == "reel" || target == null) out.strike("pinch on ${target ?: "-"}")
            ready && (target == "rod" || target == "reel") -> {
                if (rod != Rod.HOLDER || flying) { out.log("pinch on the rod: already held (${rod.name.lowercase()})"); return }
                if (who != null && who.fresh && !holdsNet(who)) takeRod(if (who === right) Rod.RIGHT else Rod.LEFT, who.palm!!, "gaze pinch, into the ${who.name} hand ($rule)")
                else takeRod(Rod.DRAG, w.rodGrip, "gaze pinch with no live hand: pinch-hold and drag to cast")
            }
            ready && target == "water" -> out.pinchOther(if (w.ice) "ice" else "water-ready")
            (w.state == Trip.State.FIGHT || w.state == Trip.State.NETTABLE) && target != "reel" -> out.pinchOther("reel-hint")
            else -> Unit
        }
    }

    private fun pinchNet(w: World, who: HandTrack?) {
        if (w.state != Trip.State.NETTABLE) { out.log("net: not usable in ${w.state}"); out.pinchOther("net-early"); return }
        if (net != Net.REST || landedT >= 0) return
        val free = hands.filter { it.fresh && !holdsRod(it) }
        val h = if (who != null && who in free) who else free.firstOrNull { it === left } ?: free.firstOrNull()
        if (h != null) takeNet(if (h === right) Net.RIGHT else Net.LEFT, "gaze pinch, into the ${h.name} hand")
        else takeNet(Net.AUTO, "gaze pinch with no live hand: scooping by itself")
    }

    /** Which hand pinched: the fresh hand whose thumb and index meet nearest the event's position, else by side. */
    private fun whichHand(at: Vec3?): Pair<HandTrack?, String> {
        val live = hands.filter { it.fresh && it.reading != null }
        if (live.isEmpty()) return null to "no live hand"
        if (at != null) {
            val best = live.minByOrNull { it.reading!!.pinchPoint.distanceTo(at) }!!
            if (best.reading!!.pinchPoint.distanceTo(at) < 0.15) return best to "nearest thumb-index"
            val palm = live.minByOrNull { it.reading!!.palm.distanceTo(at) }!!
            if (palm.reading!!.palm.distanceTo(at) < 0.20) return palm to "nearest palm"
        }
        if (live.size == 1) return live[0] to "the only live hand"
        if (at != null) return (if (at.x < 0) left else right) to "side of the body"
        return right to "default right"
    }

    // ---- The system's drag (no live hand: the rod; a gaze-pinch-hold on the reel: winding) ----

    /** A drag began on [target] at [t] (system clock, s). */
    fun dragStart(target: String?, t: Double) {
        val w = this.w ?: return
        dragOn = true
        lastDragT = t
        val fighting = w.state == Trip.State.FIGHT || w.state == Trip.State.NETTABLE
        when {
            fighting && (target == "reel" || target == "rod") -> { crankOn = true; reelDrag.reset(); dragPalm = Vec3.ZERO; out.log("crank by drag on the $target") }
            // The drag came before (or without) the pinch-down on the rod, and no hand is tracked: the drag holds it.
            rod == Rod.HOLDER && !flying && readyish(w.state) && (target == "rod" || target == "reel") && hands.none { it.fresh } ->
                takeRod(Rod.DRAG, w.rodGrip, "drag on the $target with no live hand")
            rod == Rod.LEFT || rod == Rod.RIGHT -> {
                val h = trackOf(rod)
                // The holding hand's data went stale while the pinch is held: the drag carries the rod on.
                if (!h.fresh && !flying) {
                    val from = rodPose?.first ?: w.rodGrip
                    rod = Rod.DRAG
                    castDrag.begin(from)
                    dragPalm = from
                    out.log("rod: the ${h.name} hand's data went stale, the drag carries it")
                }
            }
        }
    }

    /** The drag moved by [d] (m, the seat's frame) at [t] (system clock, s). */
    fun dragMove(d: Vec3, t: Double) {
        if (!dragOn) return
        if (t < lastDragT) return
        lastDragT = t
        if (crankOn) {
            dragPalm += d
            val turns = reelDrag.update(t, dragPalm)
            if (turns > 0) wound(turns, "drag on the reel, r ${cm(reelDrag.radius)} cm")
            return
        }
        if (rod == Rod.DRAG && !flying) {
            dragPalm += d
            handle(castDrag.sample(t, dragPalm))
            if (!flying) rodPose = dragPalm to rodDir(castDrag)
        }
    }

    fun dragEnd() {
        dragOn = false
        if (crankOn) { crankOn = false; out.log("crank by drag ended") }
    }

    // ---- The log ----

    private var stampN = 0
    private var stampStale = 0
    private var lastStampLog = 0.0
    private var lastNewAt = Double.NaN
    private val gaps = ArrayList<Double>()

    private var noData = 0

    private fun stampLog(stamp: Long?, stampNew: Boolean) {
        if (stamp == null) noData++
        else if (stampNew) {
            stampN++
            if (!lastNewAt.isNaN()) gaps += now - lastNewAt
            lastNewAt = now
        } else stampStale++
        if (now - lastStampLog >= STAMP_LOG_S) {
            lastStampLog = now
            val g = gaps.sorted()
            fun ms(q: Double) = if (g.isEmpty()) "-" else "%.0f".format(g[((g.size - 1) * q).toInt()] * 1000)
            out.log("hand stamps: $stampN new, $stampStale repeated, $noData frames without tracker data; intervals ms p50 ${ms(0.5)} p90 ${ms(0.9)} max ${ms(1.0)}; stamp unit ${if (stamps.scale > 0) "1/%.0e s".format(stamps.scale) else "?"}; " +
                "right new ${right.newCount}/frozen ${right.staleCount}, left new ${left.newCount}/frozen ${left.staleCount}")
            stampN = 0; stampStale = 0; noData = 0; gaps.clear()
        }
    }

    /** One line for the debug HUD: each hand fresh or frozen, open or a fist, its distance to the rod's grip. */
    fun debugLine(): String {
        val w = this.w
        fun one(h: HandTrack, n: String): String {
            val p = h.palm ?: return "$n 无"
            val d = w?.let { cm(p.distanceTo(it.rodGrip)) } ?: "?"
            return "$n ${if (h.fresh) "新鲜" else "冻结"} ${if (h.fist) "拳" else "张"} 离竿柄${d}cm"
        }
        return "${one(right, "右")}｜${one(left, "左")}｜竿:${rod.name}${if (kept) "(握着)" else ""} 网:${net.name} 甩:${castPhase.name}"
    }

    companion object {
        /** The rod goes back in its holder when its hand is gone this long; the net its own time. */
        const val ROD_LOST_S = 2.0
        /** v0.6.0: a kept rod goes back to its holder after the hand has been open this long. */
        const val OPEN_S = 0.3
        const val NET_LOST_S = 2.0
        const val LIVE_S = 2.0
        /** A rod held by the drag goes back when no drag comes this long. */
        const val DRAG_IDLE_S = 8.0
        const val STAMP_LOG_S = 3.0

        fun cm(m: Double) = "%.0f".format(m * 100)
        fun fmt(p: Vec3?) = if (p == null) "-" else "(%.2f, %.2f, %.2f)".format(p.x, p.y, p.z)
    }
}
