package com.vrgames.fishing.game

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vrgames.fishing.core.Bait
import com.vrgames.fishing.core.BaitRules
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lake
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.LiveHud
import com.vrgames.fishing.core.PanelLayout
import com.vrgames.fishing.core.Progress
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.core.Tutorial
import com.vrgames.fishing.core.Vec3
import com.vrgames.fishing.render.LakeView
import com.vrgames.fishing.render.Music
import com.vrgames.fishing.render.Sfx
import com.vrgames.fishing.render.Sound
import com.vrgames.fishing.render.Voice

/**
 * State the stage, its panels and both windows share (one process). [trip] is the outing at the
 * lake; the frame loop steps it and turns its events into sounds, notes and splashes. [progress]:
 * best stars and record weights (saved).
 *
 * v0.5: the hands' moves arrive from content.HandPlay (core.Angler) as [rodTaken], [cocked], [castBy], [castFailed],
 * [rodBack], [strikeBy], [reel], [net]; the step-by-step [tutorial] (text and voice) follows them.
 *
 * v0.5.1: every change goes through [changed] (the counter [version] and the panels' [live] snapshot, which the frame
 * also renews up to 10 times a second, so the fight's numbers on the left panel move as they change); the bait has its
 * own button on the left panel ([baitButton], [pickBait]; core.BaitRules) and the tutorial's first step.
 */
object Game {
    private const val PREFS = "fishing"
    private const val TAG = "FishGame"

    enum class Phase { CARD, INTRO, PLAYING, RESULT }

    val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    var progress = Progress(); private set
    var lake by mutableStateOf(Lakes.ALL[0]); private set
    var trip: Trip? = null; private set
    var phase by mutableStateOf(Phase.CARD)
    var version by mutableIntStateOf(0); private set
    /** v0.5.1: what the left panel shows that can change (a fight's tension, strength, distance, …), renewed every frame. */
    var live by mutableStateOf(LiveHud()); private set
    private var liveAt = 0L
    /** v0.5.1: the left panel lists the baits to pick one. */
    var baitPicker by mutableStateOf(false); private set
    /** Turns of the reel since the fish was hooked (shown on the left panel). */
    private var fightTurns = 0.0
    var note by mutableStateOf<String?>(null)
    var noteVersion by mutableIntStateOf(0); private set
    var menuOpen by mutableStateOf(false)
    var confirmRestart by mutableStateOf(false)
    var musicOn by mutableStateOf(true); private set
    /** Where the head points, degrees from straight ahead (logged; v0.5: casts go where the arm throws, not the head). */
    var aimYaw = 0.0
    var trophiesBefore = 0
    var music: Music? = null
    var sound: Sound? = null
    var voice: Voice? = null
    var view: LakeView? = null
    private var met = 0

    var window by mutableStateOf<String?>(null); private set
    private val windowCopies = HashMap<String, Any>()
    var closeWindow: (String) -> Unit = {}

    fun windowShown(id: String, copy: Any? = null) {
        if (copy != null) windowCopies[id] = copy
        window = id
    }

    fun windowGone(id: String, copy: Any? = null) {
        if (copy != null && windowCopies[id] !== copy) return
        windowCopies.remove(id)
        if (window == id) window = null
    }

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            musicOn = p.getBoolean("music", true)
            progress = Progress.load(p.getString("progress", null))
            tutorialDone = p.getBoolean(KEY_TUTORIAL, false)
        }
        AutoPilot.register(context)
    }

    /** Something the panels show changed: the counter, and the left panel's snapshot now. */
    fun changed() {
        version++
        refreshLive(force = true)
    }

    /** The left panel's snapshot ([force]: now; else at most every 100 ms, from the frame). */
    fun refreshLive(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - liveAt < LIVE_MS) return
        liveAt = now
        val t = trip
        val canPick = phase == Phase.INTRO || phase == Phase.PLAYING
        if (baitPicker && (t == null || !canPick || !BaitRules.canChange(t.state))) baitPicker = false
        live = synchronized(lock) { LiveHud.of(t, version, fightTurns, baitPicker, canPick) }
    }

    /**
     * v0.5.1: the panels up now (core.PanelLayout.shown, tested there for overlaps; the stage shows exactly these, and
     * each panel draws its content only while it is in this list). Reads Compose states.
     */
    fun panelsShown(): List<PanelLayout.Slot> {
        val board = phase == Phase.PLAYING && (tutorialStep > 0 || netHint)
        return PanelLayout.shown(phase.name, menuOpen, board, board && tutorialStep in Tutorial.OUT, note != null)
    }

    fun panelOn(id: String) = panelsShown().any { it.id == id }

    val trophies get() = progress.let { if (it.gold) 3 else if (it.silver) 2 else if (it.bronze) 1 else 0 }

    /**
     * A short note in front; while the tutorial board is up, it goes on the board instead (one board at a time). On the
     * cards (lake, results) it is a line inside the card (v0.5.1: the note line stood over them).
     */
    fun say(text: String?) {
        if (text != null && phase == Phase.PLAYING && (tutorial.active || netHint)) { boardNote = text; changed(); return }
        note = text
        noteVersion++
    }

    fun start() = open(Lakes.MAIN.firstOrNull { (progress.best[it.id] ?: 0) == 0 } ?: Lakes.MAIN.last())

    fun open(l: Lake) {
        reeled = 0.0; reeledLogged = 0
        if (!progress.open(l)) {
            say(if (l.hidden) "拿到${if (l.id == "night") "银" else "金"}奖杯才能来这里。" else "先完成前面的钓场。")
            return
        }
        lake = l
        synchronized(lock) { trip = Trip(l) }
        met = 0
        netHint = false
        baitPicker = false
        fightTurns = 0.0
        phase = Phase.INTRO
        Log.i(TAG, "open ${l.id}: bait ${trip?.bait?.name?.lowercase()}")
        changed()
    }

    fun begin() {
        phase = Phase.PLAYING
        if (!tutorialDone && !lake.ice) startTutorial()
        else say(when {
            lake.ice -> "拿起鱼竿往前一甩（或者看着冰洞捏一下），钩子就放进冰洞。浮漂整个沉下去时提竿。"
            else -> "右前方竿架上的竿柄在发光：握住它，或者看着它捏一下手指，拿起鱼竿；举过肩往后带一点，听到「咔」再往前一甩。"
        })
        changed()
    }

    private fun hookedLine() = "中鱼了！用任意一只手在身前画圈收线（不用捏）；线变红、鱼往外冲时先停手。"

    // ---- The step-by-step tutorial (v0.5) ----

    val tutorial = Tutorial()
    var tutorialStep by mutableIntStateOf(0); private set
    var tutorialDone = false; private set
    /** A line on the board under the step (a setback's hint, or a note while the board is up). */
    var boardNote by mutableStateOf<String?>(null); private set
    /** The net can be used (outside the tutorial): the board shows how (step 7's text). */
    var netHint by mutableStateOf(false); private set
    private var playTime = 0.0
    private var stepAt = 0.0
    private var lastReelAt = 0.0

    private fun startTutorial() {
        tutorial.start()
        netHint = false
        boardNote = null
        // The board is up now: a note still waiting would stand where the board goes (v0.5.1).
        note = null
        tutorialStep = tutorial.step
        stepAt = playTime
        Log.i(TAG, "tutorial step ${tutorial.step} ${tutorial.header}")
        voice?.play(tutorial.current!!.voice)
        changed()
    }

    /** Menu 「新手教学（从头再学一遍）」: from taking the rod, at this lake (or 西湖 from the ice). */
    fun tutorialFromMenu() {
        menuOpen = false
        val t = trip
        val ok = phase == Phase.PLAYING && !lake.ice && t != null && (t.state == Trip.State.READY || t.state == Trip.State.LANDED) && !rodInHand
        if (!ok) {
            open(if (lake.ice) Lakes.ALL[0] else lake)
            phase = Phase.PLAYING
        }
        startTutorial()
    }

    fun skipTutorial() {
        Log.i(TAG, "tutorial skipped at step ${tutorial.step}")
        endTutorial()
    }

    /** The last page's 「开始钓鱼」 (or skipping): the board goes, and the tutorial counts as done. */
    fun endTutorial() {
        tutorial.stop()
        tutorialStep = 0
        boardNote = null
        tutorialDone = true
        prefs?.edit()?.putBoolean(KEY_TUTORIAL, true)?.apply()
        voice?.stop()
        trip?.let { t -> if (phase == Phase.PLAYING && lake.missions.all { it.check(t) }) finish() }
        changed()
    }

    /** v0.5.1: the bait step's 「知道了」 (the step also moves on when a bait is picked). */
    fun tutorialAck() {
        Log.i(TAG, "tutorial: 知道了 at step ${tutorial.step}")
        tut(Tutorial.Ev.BAIT)
    }

    /** 「再听一遍」. */
    fun replayVoice() {
        val id = when {
            tutorial.active -> tutorial.hint?.voice ?: tutorial.current?.voice
            netHint -> Tutorial.NET.voice
            else -> null
        } ?: return
        voice?.play(id)
    }

    private fun tut(e: Tutorial.Ev) {
        if (!tutorial.active) return
        val before = tutorial.step
        if (!tutorial.on(e)) return
        if (tutorial.step != before) {
            tutorialStep = tutorial.step
            stepAt = playTime
            boardNote = tutorial.hint?.text
            Log.i(TAG, "tutorial step ${tutorial.step} ${tutorial.header} (after $e)")
            if (tutorial.step == Tutorial.TOTAL + 1) { tutorialDone = true; prefs?.edit()?.putBoolean(KEY_TUTORIAL, true)?.apply() }
            note = null
            // A setback's own line first; the step's after it would cut it off, so the step's voice waits for 「再听一遍」.
            voice?.play(tutorial.hint?.voice ?: tutorial.current!!.voice)
        } else {
            boardNote = tutorial.hint?.text
            Log.i(TAG, "tutorial hint at step ${tutorial.step}: ${tutorial.hint?.text} (after $e)")
            tutorial.hint?.voice?.let { voice?.play(it) }
        }
        changed()
    }

    // ---- The player's moves (from the hands, the pinches and the drags: content.HandPlay / core.Angler) ----

    fun rodTaken(how: String) {
        rodInHand = true
        sound?.play(Sfx.BAIT)
        Log.i(TAG, "rod taken ($how)")
        tut(Tutorial.Ev.ROD_TAKEN)
        changed()
    }

    /** The rod drawn back over the shoulder: 「咔」. */
    fun cocked() {
        sound?.play(Sfx.COCK)
        tut(Tutorial.Ev.COCKED)
    }

    /** A pinch the stage does not answer itself (core.Angler.Out.pinchOther). */
    fun pinchOther(what: String) {
        when (what) {
            // v0.5.1: the bait box opens the same picker as the left panel's 「换饵」 (or says why not now).
            "bait" -> baitButton("box")
            "ice" -> press()
            "water-ready" -> if (!tutorial.active) say("先拿起鱼竿：右前方竿架上的竿柄在发光，握住它，或者看着它捏一下手指。")
            "net-early" -> say("鱼还没拉到岸边，抄网还用不上。继续收线，鱼没力气、靠到岸边时抄网会亮。")
            "reel-hint" -> if (!tutorial.active) say("收线：用任意一只手在身前画圈；也可以看着绕线轮捏住手指转圈，或者点左边面板上的「收线」。")
        }
    }

    /** A strike by a pinch or by the ice drop (the old one-button press); [Trip.press]. */
    fun press() {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        val r = synchronized(lock) { t.press() }
        Log.i("FishHands", "press -> $r (${t.state})")
        when (r) {
            "cast" -> { sound?.play(Sfx.CAST); view?.plop(t); sound?.play(Sfx.PLOP, Vec3(0.0, 0.0, -t.castDist)) }
            "hooked" -> { fightTurns = 0.0; sound?.play(Sfx.STRIKE); say(hookedLine()); tut(Tutorial.Ev.HOOKED) }
            "spooked" -> { sound?.play(Sfx.WRONG); say("太早了！那只是鱼在试探，把它吓跑了。等浮漂整个沉下去再提竿。") }
            "early" -> { sound?.play(Sfx.WRONG); if (tutorial.active) tut(Tutorial.Ev.EARLY) else say(Tutorial.FAIL_EARLY) }
        }
        changed()
    }

    /** A cast by the arm: [power] from the throw's speed, [yaw] where the palm went, [launch] for the float's arc. */
    fun castBy(power: Double, yaw: Double = 0.0, launch: Double = 30.0) {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        t.aimYaw = yaw.coerceIn(-70.0, 70.0)
        view?.launchDeg = launch
        // The tutorial's cast brings its easy fish.
        if (tutorial.active && tutorial.step in Tutorial.EASY) t.easyNext = true
        val r = synchronized(lock) { t.cast(power) }
        Log.i("FishHands", "cast power=${"%.2f".format(power)} yaw=${"%.0f".format(t.aimYaw)} launch=${"%.0f".format(launch)} -> $r dist=${"%.1f".format(t.castDist)}${if (t.easy) " (tutorial fish)" else ""}")
        if (r == "cast") {
            sound?.play(Sfx.CAST)
            sound?.play(Sfx.WHIRR, Vec3(0.2, 0.9, -0.5))
            view?.plop(t)
            sound?.play(Sfx.PLOP, Vec3(0.0, 0.0, -t.castDist))
            tut(Tutorial.Ev.CAST)
        }
        changed()
    }

    /** The throw did not count: "slow" (no cast) or "lost" (tracking lost mid-throw). */
    fun castFailed(why: String) {
        sound?.play(Sfx.WRONG)
        if (tutorial.active) tut(if (why == "lost") Tutorial.Ev.FAIL_LOST else Tutorial.Ev.FAIL_SLOW)
        else {
            say(if (why == "lost") Tutorial.FAIL_LOST else Tutorial.FAIL_SLOW)
            voice?.play(if (why == "lost") "fail_lost" else "fail_slow")
        }
    }

    /** The rod went back into its holder ([why]: "landed" the float is on the water; "lost" the hand; …). */
    fun rodBack(why: String) {
        sound?.play(Sfx.CLICK, Vec3(0.2, 0.9, -0.5))
        rodInHand = false
        if (why == "landed") {
            tut(Tutorial.Ev.FLOAT_LANDED)
            if (!tutorial.active && trip?.state == Trip.State.WAIT) say("鱼竿放回竿架了，盯着浮漂。整个沉下去时，手在竿柄旁往上一抬，或者捏一下手指。")
        }
        if (why == "opened" && !tutorial.active) say(if (trip?.state == Trip.State.WAIT || trip?.state == Trip.State.BITE) "鱼竿放回竿架了，盯着浮漂。整个沉下去时，手在竿柄旁往上一抬，或者捏一下手指。" else "鱼竿放回竿架了。")
        if (why == "lost") { if (tutorial.active) tut(Tutorial.Ev.ROD_LOST) else say(Tutorial.LOST_ROD) }
        changed()
    }

    /** v0.6.0: the float landed and the hand still grips the rod: it stays in the hand. */
    fun rodKept() {
        Log.i(TAG, "rod kept in the hand after the cast")
        tut(Tutorial.Ev.FLOAT_LANDED)
        if (!tutorial.active && trip?.state == Trip.State.WAIT) say("握着鱼竿等鱼。浮漂整个沉下去时，拿竿的手往上一抬就是提竿；想歇一会儿就张开手，鱼竿会放回竿架。")
        changed()
    }

    /** A strike: a hand lifted at the grip, or a pinch while the float is down. */
    fun strikeBy(how: String = "") {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        val r = synchronized(lock) { t.strike() }
        Log.i("FishHands", "strike ($how) -> $r")
        when (r) {
            "hooked" -> { fightTurns = 0.0; sound?.play(Sfx.STRIKE); say(hookedLine()); tut(Tutorial.Ev.HOOKED) }
            "spooked" -> { sound?.play(Sfx.WRONG); say("浮漂只是轻轻一点，鱼还在试探，被你吓跑了。等它整个沉下去再提竿。") }
            "early" -> { sound?.play(Sfx.WRONG); if (tutorial.active) tut(Tutorial.Ev.EARLY) else say(Tutorial.FAIL_EARLY) }
        }
        changed()
    }

    /** v0.3: hand tracking is on (the board speaks of hands), and what the hands hold. */
    var handsOn by mutableStateOf(false)
    /** A live (fresh) hand was seen in the last two seconds. */
    var handsLive by mutableStateOf(false)
    var rodInHand by mutableStateOf(false); private set
    var netInHand by mutableStateOf(false); private set
    /** Debug builds: the HUD's line about the hands (fresh or frozen, fist, distance to the grip). */
    var debugHands by mutableStateOf("")

    fun inHand(rod: Boolean? = null, net: Boolean? = null) {
        Log.i("FishHands", "in hand: rod=$rod net=$net")
        rod?.let { rodInHand = it }
        net?.let { netInHand = it }
        changed()
    }

    private var reeled = 0.0
    private var reeledLogged = 0
    fun reel(turns: Double, by: String = "button") {
        val t = trip ?: return
        if (t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE) { reeled += turns; fightTurns += turns; lastReelAt = playTime }
        if (reeled.toInt() >= reeledLogged + 2) { reeledLogged = reeled.toInt(); Log.i("FishHands", "reeled $reeledLogged turns ($by), ${t.state} ${"%.1f".format(t.distance)} m tension ${"%.2f".format(t.tension)}") }
        if (phase != Phase.PLAYING) return
        synchronized(lock) { t.reel(turns) }
    }

    /** The HUD's 「收线」: a quarter turn, and the crank shows it. */
    fun reelButton() {
        reel(0.25, "button")
        crankBy(0.25)
        sound?.play(Sfx.REEL)
    }

    /** Shows turns of the crank wound by the HUD's button (set by the stage). */
    var crankBy: (Double) -> Unit = {}

    /** v0.5 HUD 「收竿重抛」 while waiting: wind the float in to cast again. */
    fun reelIn() {
        val t = trip ?: return
        if (synchronized(lock) { t.reelIn() }) { sound?.play(Sfx.REEL); say("收回来了，可以换饵，再重新拿竿抛。"); changed() }
    }

    fun net() {
        val t = trip ?: return
        val ok = synchronized(lock) { t.net() }
        Log.i("FishHands", "net -> $ok (${t.state})")
        if (!ok) {
            sound?.play(Sfx.NIBBLE)
            say(if (t.state == Trip.State.FIGHT) "鱼还没拉到岸边，抄网还用不上。继续收线，鱼没力气、靠到岸边时抄网会亮。" else "还没有鱼可以抄。")
            return
        }
        afterLanding(t)
    }

    /** The net went back on its rest ([why] from core.Angler). */
    fun netBack(why: String) {
        if (why.startsWith("the fish swam off")) say("鱼又往外冲了，抄网先放回去。继续收线，等它没力气再抄。")
    }

    /** 「放下抄网」 on the board. */
    var dropNet: () -> Unit = {}

    /**
     * v0.5.1: the left panel's 「换饵：…」 (and the bait box, [how] "box"): opens or closes the bait list while the bait can
     * be changed (the line in: before the cast, after a catch, a break or winding in; the lake's card too); from the cast
     * until the fish is landed or lost it is grey and says why.
     */
    fun baitButton(how: String = "button") {
        val t = trip ?: return
        val canPick = phase == Phase.INTRO || phase == Phase.PLAYING
        val why = if (!canPick) "现在不能换饵" else BaitRules.lockedWhy(t.state)
        if (why != null) {
            Log.i(TAG, "bait $how refused in ${t.state}: $why")
            sound?.play(Sfx.NIBBLE)
            say(why + "。")
            changed()
            return
        }
        baitPicker = !baitPicker
        Log.i(TAG, "bait $how: picker ${if (baitPicker) "open" else "closed"} (on the hook: ${t.bait.name.lowercase()})")
        if (baitPicker && how == "box") say("在左边面板上选一种饵。")
        changed()
    }

    /** v0.5.1: a bait picked in the list ([how]: "picker"; "auto" the debug angler). */
    fun pickBait(b: Bait, how: String = "picker") {
        val t = trip ?: return
        val canPick = phase == Phase.INTRO || phase == Phase.PLAYING
        val why = if (!canPick) "现在不能换饵" else BaitRules.lockedWhy(t.state)
        if (why != null) { Log.i(TAG, "bait pick ${b.name.lowercase()} refused in ${t.state}: $why"); say(why + "。"); baitPicker = false; changed(); return }
        val before = t.bait
        t.bait = b
        baitPicker = false
        if (how != "auto") {
            sound?.play(Sfx.BAIT)
            Log.i(TAG, "bait picked ${b.name.lowercase()} (was ${before.name.lowercase()}) via $how; hint: ${BaitRules.hint(t) ?: "-"}")
            say(if (b == before) "还是挂${b.label}。" else "换上了${b.label}。")
            tut(Tutorial.Ev.BAIT)
        }
        changed()
    }

    fun wrapUp() {
        val t = trip ?: return
        if (t.done) finish()
    }

    /** Dismiss the net's board (outside the tutorial). */
    fun hideNetHint() { netHint = false; boardNote = null; changed() }

    private fun afterLanding(t: Trip) {
        val c = t.landedLast ?: return
        progress.catchOf(c)
        save()
        sound?.play(Sfx.LANDED)
        view?.landed(c.species, 0.35f)
        view?.fishInBasket(t.catches.size)
        netHint = false
        boardNote = null
        // v0.5.2: the goal this fish counts for, with how far it has got (「目标：钓到 3 条鲫鱼（1/3）」).
        val goal = lake.missions.firstOrNull { it.fish == c.species }?.let { "　目标：${it.text}${it.progress(t)}" } ?: ""
        say("钓到一条${Fish.byId(c.species).name}，${c.jin} 斤！${if (t.landedBy == "reel") "（收线拉上岸）" else ""}$goal")
        Log.i(TAG, "goals: " + lake.missions.joinToString(" | ") { (if (it.check(t)) "✔ " else "○ ") + it.text + it.progress(t) })
        Log.i(TAG, "landed ${c.species} ${c.jin} jin by ${t.landedBy}")
        tut(Tutorial.Ev.FISH_LANDED)
        val now = lake.missions.count { it.check(t) }
        if (now > met) { met = now; sound?.play(Sfx.MISSION) }
        if (lake.missions.all { it.check(t) } && !tutorial.active) finish()
        changed()
    }

    // ---- The frame ----

    fun tick(dt: Double) {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        playTime += dt
        val evs = synchronized(lock) {
            t.events.clear()
            t.step(dt)
            t.events.toList()
        }
        for (e in evs) {
            val inTutorial = tutorial.active
            when (e) {
                "nibble" -> sound?.play(Sfx.NIBBLE)
                "bite" -> { sound?.play(Sfx.BITE); say("沉下去了！快提竿：手在竿柄旁往上一抬，或者看着浮漂捏一下手指。") }
                "biting" -> { sound?.play(Sfx.NIBBLE); if (!inTutorial) say("鱼还咬着！手在竿柄旁往上一抬，或者捏一下手指。") }
                "missed" -> if (!inTutorial) { say(Tutorial.MISSED); voice?.play("missed") }
                "run" -> sound?.play(Sfx.RUN)
                "break" -> { sound?.play(Sfx.SNAP); netHint = false; if (!inTutorial) { say(Tutorial.SNAPPED); voice?.play("snapped") } }
                "nettable" -> {
                    sound?.play(Sfx.SPLASH)
                    if (!inTutorial) { netHint = true; boardNote = null; note = null; voice?.play(Tutorial.NET.voice); Log.i(TAG, "net hint shown") }
                }
                "away" -> { netHint = false; boardNote = null }
                // A fish reeled up the bank (the net's own landing goes through net()); the tutorial hears it there.
                "landed" -> { afterLanding(t); continue }
            }
            Tutorial.ofTripEvent(e)?.let { tut(it) }
        }
        // The tutorial's extra help for winding: 8 s at the reeling step without a turn.
        if (tutorial.step == Tutorial.REEL && playTime - maxOf(stepAt, lastReelAt) > Tutorial.REEL_HELP_S && tutorial.reelHelp()) {
            boardNote = tutorial.hint?.text
            Log.i(TAG, "tutorial hint at step ${Tutorial.REEL}: reel help")
            voice?.play("tut6b")
            changed()
        }
        if (evs.isNotEmpty()) changed()
        // The fight's numbers on the left panel: a new snapshot up to 10 times a second (v0.5.1).
        refreshLive()
    }

    private fun finish() {
        val t = trip ?: return
        trophiesBefore = trophies
        progress.record(lake.id, t.stars())
        save()
        phase = Phase.RESULT
        // The results say what was caught; the note line would stand over them (v0.5.1).
        note = null
        sound?.play(Sfx.MISSION)
        Log.i(TAG, "finish ${lake.id} stars=${t.stars()} casts=${t.casts} breaks=${t.breaks} catches=${t.catches.size}")
        changed()
    }

    /** Debug hook `result`: the results panel now (test setup, as if the lake were done). */
    fun debugFinish() {
        if (trip == null) return
        if (tutorial.active) { tutorial.stop(); tutorialStep = 0; boardNote = null }
        finish()
    }

    fun next() = open(Lakes.MAIN.firstOrNull { (progress.best[it.id] ?: 0) == 0 } ?: Lakes.MAIN.last())

    fun again() = open(lake)

    fun toCard() {
        phase = Phase.CARD
        changed()
    }

    private fun save() {
        prefs?.edit()?.putString("progress", progress.save())?.apply()
    }

    fun restart() {
        progress = Progress()
        save()
        phase = Phase.CARD
        changed()
    }

    fun toggleMusic() {
        musicOn = !musicOn
        music?.muted = !musicOn
        prefs?.edit()?.putBoolean("music", musicOn)?.apply()
    }

    fun grant(stars: Int) {
        for (l in Lakes.MAIN) progress.best[l.id] = stars
        save()
        changed()
    }

    /** Debug hook `trip`: a fish on the line now ("fight", "nettable", "bite"), as test setup. */
    fun debugTrip(to: String) {
        val t = trip ?: return
        if (phase != Phase.PLAYING) { phase = Phase.PLAYING }
        val st = when (to) { "nettable" -> Trip.State.NETTABLE; "bite" -> Trip.State.BITE; else -> Trip.State.FIGHT }
        synchronized(lock) { t.debugState(st) }
        if (st != Trip.State.BITE) fightTurns = 0.0
        Log.i(TAG, "debug trip -> ${t.state} (${t.fish?.id}, ${"%.1f".format(t.distance)} m)")
        if (st == Trip.State.NETTABLE && !tutorial.active) { netHint = true; boardNote = null; note = null; voice?.play(Tutorial.NET.voice) }
        changed()
    }

    // Debug hooks (AutoPilot).
    var debugOpen by mutableStateOf<String?>(null)
    var debugLook by mutableStateOf<Double?>(null)
    /** Debug (emulator screenshots): the scene turned this many degrees about the eyes (its fixed camera sees the sides). */
    @Volatile var debugTurn: Float? = null
    var faceRequest by mutableIntStateOf(0)
    /** Debug hook `replace`: measure the eyes again; the head reads all zeros for [debugZeroHeadS] seconds first. */
    var debugZeroHeadS = 0.0
    var placeRequest by mutableIntStateOf(0)
    /** Debug hook `gaze`: a gaze-and-pinch on "rod", "reel", "bait", "net" or "water", through the stage's pinch handler. */
    var debugGaze by mutableStateOf<String?>(null)
    /** Debug hook `drag`: "cast" or "crank", a system drag through the stage's drag handler. */
    var debugDrag by mutableStateOf<String?>(null)
    /** Debug hook `hands <scenario>`: the simulated hands (core.SimHands); null: the tracker. */
    @Volatile var simScenario: String? = null
    /** Debug builds: game time runs this many times faster than the frames say (the emulator draws ~1 frame a second). */
    @Volatile var debugTimeScale = 1.0
    /** v0.5.1: the tutorial's "done" (a new key, so players of 0.5.0 and before see it once more, with its new bait step). */
    private const val KEY_TUTORIAL = "tutorial_done_v51"
    /** The left panel's snapshot is renewed at most this often from the frame (ms): 10 a second. */
    private const val LIVE_MS = 100L
}
