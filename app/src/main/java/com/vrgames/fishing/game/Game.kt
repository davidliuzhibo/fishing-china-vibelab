package com.vrgames.fishing.game

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vrgames.fishing.core.Bait
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lake
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.Progress
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.core.Vec3
import com.vrgames.fishing.render.LakeView
import com.vrgames.fishing.render.Music
import com.vrgames.fishing.render.Sfx
import com.vrgames.fishing.render.Sound

/**
 * State the stage, its panels and both windows share (one process). [trip] is the outing at the
 * lake; the frame loop steps it and turns its events into sounds, notes and splashes. [progress]:
 * best stars and record weights (saved).
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
    var note by mutableStateOf<String?>(null)
    var noteVersion by mutableIntStateOf(0); private set
    var menuOpen by mutableStateOf(false)
    var confirmRestart by mutableStateOf(false)
    var musicOn by mutableStateOf(true); private set
    /** Where the head points, degrees from straight ahead (the cast goes there). */
    var aimYaw = 0.0
    var trophiesBefore = 0
    var music: Music? = null
    var sound: Sound? = null
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
        }
        AutoPilot.register(context)
    }

    val trophies get() = progress.let { if (it.gold) 3 else if (it.silver) 2 else if (it.bronze) 1 else 0 }

    fun say(text: String?) {
        note = text
        noteVersion++
    }

    fun start() = open(Lakes.MAIN.firstOrNull { (progress.best[it.id] ?: 0) == 0 } ?: Lakes.MAIN.last())

    fun open(l: Lake) {
        if (!progress.open(l)) {
            say(if (l.hidden) "拿到${if (l.id == "night") "银" else "金"}奖杯才能来这里。" else "先完成前面的钓场。")
            return
        }
        lake = l
        synchronized(lock) { trip = Trip(l) }
        met = 0
        phase = Phase.INTRO
        version++
    }

    fun begin() {
        phase = Phase.PLAYING
        say(if (lake.ice) "捏一下，把钩子放进冰洞。浮漂往下一沉就捏！" else "看着想抛的地方，捏一下开始蓄力，力度合适时再捏一下抛出。")
        version++
    }

    // ---- The player's moves ----

    /** The one pinch: swing, cast, strike. */
    fun press() {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        t.aimYaw = aimYaw.coerceIn(-70.0, 70.0)
        val r = synchronized(lock) { t.press() }
        when (r) {
            "power" -> sound?.play(Sfx.BAIT)
            "cast" -> { sound?.play(Sfx.CAST); view?.plop(t); sound?.play(Sfx.PLOP, Vec3(0.0, 0.0, -t.castDist)) }
            "hooked" -> { sound?.play(Sfx.STRIKE); say("中鱼了！转绕线轮收线，鱼冲的时候停手。") }
            "spooked" -> { sound?.play(Sfx.WRONG); say("太早了！那只是鱼在试探，把它吓跑了。等浮漂整个沉下去再捏。") }
        }
        version++
    }

    fun reel(turns: Double) {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        synchronized(lock) { t.reel(turns) }
    }

    fun net() {
        val t = trip ?: return
        val ok = synchronized(lock) { t.net() }
        if (!ok) {
            say(if (t.state == Trip.State.FIGHT) "鱼还有力气、离得太远，先收线遛一遛。" else "还没有鱼可以抄。")
            return
        }
        afterLanding(t)
    }

    fun cycleBait() {
        val t = trip ?: return
        val all = Bait.entries
        t.bait = all[(all.indexOf(t.bait) + 1) % all.size]
        sound?.play(Sfx.BAIT)
        say("换上了${t.bait.label}。")
        version++
    }

    fun wrapUp() {
        val t = trip ?: return
        if (t.done) finish()
    }

    private fun afterLanding(t: Trip) {
        val c = t.landedLast ?: return
        progress.catchOf(c)
        save()
        sound?.play(Sfx.LANDED)
        view?.landed(c.species, 0.35f)
        view?.fishInBasket(t.catches.size)
        say("钓到一条${Fish.byId(c.species).name}，${c.jin} 斤！")
        val now = lake.missions.count { it.check(t) }
        if (now > met) { met = now; sound?.play(Sfx.MISSION) }
        if (lake.missions.all { it.check(t) }) finish()
        version++
    }

    // ---- The frame ----

    fun tick(dt: Double) {
        val t = trip ?: return
        if (phase != Phase.PLAYING) return
        val evs = synchronized(lock) {
            t.events.clear()
            t.step(dt)
            t.events.toList()
        }
        for (e in evs) when (e) {
            "nibble" -> sound?.play(Sfx.NIBBLE)
            "bite" -> { sound?.play(Sfx.BITE); say("沉下去了！快捏！") }
            "missed" -> say("没捏到，鱼跑了。再等等……")
            "run" -> sound?.play(Sfx.RUN)
            "break" -> { sound?.play(Sfx.SNAP); say("线断了！鱼冲的时候要停手放线。") }
            "nettable" -> { sound?.play(Sfx.SPLASH); say("鱼没力气了，捏一下右边的抄网！") }
        }
        if (evs.isNotEmpty()) version++
    }

    private fun finish() {
        val t = trip ?: return
        trophiesBefore = trophies
        progress.record(lake.id, t.stars())
        save()
        phase = Phase.RESULT
        sound?.play(Sfx.MISSION)
        Log.i(TAG, "finish ${lake.id} stars=${t.stars()} casts=${t.casts} breaks=${t.breaks} catches=${t.catches.size}")
        version++
    }

    fun next() = open(Lakes.MAIN.firstOrNull { (progress.best[it.id] ?: 0) == 0 } ?: Lakes.MAIN.last())

    fun again() = open(lake)

    fun toCard() {
        phase = Phase.CARD
        version++
    }

    private fun save() {
        prefs?.edit()?.putString("progress", progress.save())?.apply()
    }

    fun restart() {
        progress = Progress()
        save()
        phase = Phase.CARD
        version++
    }

    fun toggleMusic() {
        musicOn = !musicOn
        music?.muted = !musicOn
        prefs?.edit()?.putBoolean("music", musicOn)?.apply()
    }

    fun grant(stars: Int) {
        for (l in Lakes.MAIN) progress.best[l.id] = stars
        save()
        version++
    }

    // Debug hooks (AutoPilot).
    var debugOpen by mutableStateOf<String?>(null)
    var debugLook by mutableStateOf<Double?>(null)
    var faceRequest by mutableIntStateOf(0)
}
