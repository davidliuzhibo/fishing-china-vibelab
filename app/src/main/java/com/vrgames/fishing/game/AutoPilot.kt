package com.vrgames.fishing.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.vrgames.fishing.core.Bait
import com.vrgames.fishing.core.BaitRules
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.PanelLayout
import com.vrgames.fishing.core.PanelProbe
import com.vrgames.fishing.core.Trip

/**
 * Test hook, debug builds only: fishes without hands (a careful angler: the right bait and spot,
 * reeling only while the fish is calm). Results go to logcat (tags FishTest, FishHands, FishInput, FishGame).
 *
 *   adb shell am broadcast -a com.vrgames.fishing.TEST -p com.vrgames.fishing --es cmd walk
 *
 * cmd: start · open --es id <lake> · begin · auto (fish this lake until done) · walk (every lake) ·
 * grant --es id <stars> · look --es id <yaw> · turn --es id <deg>|off (the scene about the eyes, for screenshots) · window --es id log|map|menu|close · reset · stop · state · hud ·
 * replace --es id <zero seconds> (measure the eyes again, the head reading zeros first) · quit
 *
 * v0.5 hand and input tests (all through the same handlers the headset's input reaches):
 * - hands --es id <scenario>|off: core.SimHands instead of the tracker — fist, cast, cast60, freeze, noisy, lapnet,
 *   reelface, reelpedal, reelsmall, reelonly, tutorial (this one also starts the tutorial); game time runs
 *   [Game.debugTimeScale] (default 12) times faster while they are on, the hands sampled at 90 Hz inside each frame;
 * - speed --es id <x>: that time scale;
 * - gaze --es id rod|reel|bait|net|water: a system pinch-down on that target, through the stage's pinch handler
 *   (with the simulated hand's position when they are on);
 * - drag --es id cast|crank: a system drag through the stage's drag handlers (cast: the rod pinch-held with no hand
 *   tracking, raised and thrown; crank: a pinch-hold on the reel circled);
 * - trip --es id fight|nettable|bite: a fish on the line now (test setup);
 * - tutorial --es id on|skip|end|ok (ok: the bait step's 「知道了」); netdrop (「放下抄网」).
 *
 * v0.5.1:
 * - panels: each panel up now, its rectangle in angles from the eye (as measured where drawn), the gaps between them,
 *   overlaps, and how far they keep from the rod, net, bait box, the float's water and the fish at the bank;
 * - ui: the left panel's texts as last composed (bait button, enabled or grey and why, the fight's tension, strength,
 *   distance, reel turns), when and how often it was composed, and the game's own values beside them;
 * - bait --es id button|box|ok|worm|corn|dough|spoon: the left panel's 「换饵」 (button, the same handler as its click),
 *   the bait box (a gaze-pinch through the stage's pinch handler), the tutorial's 「知道了」, or a bait picked in the
 *   list (its click handler; the list is opened with the button first when it is not open);
 * - result: the results panel now (test setup); window --es id menuoff: close the menu.
 */
object AutoPilot {
    const val ACTION = "com.vrgames.fishing.TEST"
    const val TAG = "FishTest"
    private val main = Handler(Looper.getMainLooper())
    var debug = false; private set
    private var walkAt = 0
    private var auto = false

    fun register(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        debug = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // 2026-11-19: the menu's 退出, for the emulator test (every window of the app should go).
                if (i.getStringExtra("cmd") == "quit") { android.util.Log.i(TAG, "cmd quit"); com.vrgames.fishing.platform.AppExit.finishAll(c) }
                else command(i.getStringExtra("cmd") ?: "", i.getStringExtra("id"))
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "ready")
    }

    fun ready() = Log.i(TAG, "lake ready")

    private fun command(cmd: String, id: String?) {
        Log.i(TAG, "cmd $cmd ${id.orEmpty()}")
        when (cmd) {
            "start" -> Game.start()
            "open" -> Lakes.ALL.firstOrNull { it.id == id }?.let { Game.open(it) }
            "begin" -> Game.begin()
            "auto" -> { auto = true; main.post(::angle) }
            "hands" -> {
                main.removeCallbacksAndMessages(null); auto = false
                val sc = id?.takeIf { it != "off" && it.isNotEmpty() }
                Game.simScenario = sc
                Game.debugTimeScale = if (sc == null) 1.0 else if (Game.debugTimeScale > 1.0) Game.debugTimeScale else 12.0
                Log.i(TAG, "simulated hands ${sc ?: "off"} (time x${Game.debugTimeScale})")
                // v0.5.1: a tutorial already running (e.g. past its bait step) goes on from where it is.
                if (sc == "tutorial" && !Game.tutorial.active) Game.tutorialFromMenu()
            }
            "speed" -> { Game.debugTimeScale = (id?.toDoubleOrNull() ?: 1.0).coerceIn(0.1, 60.0); Log.i(TAG, "time x${Game.debugTimeScale}") }
            "drag" -> Game.debugDrag = id
            "trip" -> Game.debugTrip(id ?: "fight")
            "tutorial" -> when (id) { "skip" -> Game.skipTutorial(); "end" -> Game.endTutorial(); "ok" -> Game.tutorialAck(); else -> Game.tutorialFromMenu() }
            "panels" -> panels()
            "ui" -> ui()
            "bait" -> when (val b = id ?: "button") {
                "button" -> Game.baitButton("button")
                "box" -> Game.debugGaze = "bait"
                "ok" -> Game.tutorialAck()
                else -> Bait.entries.firstOrNull { it.name.equals(b, ignoreCase = true) }?.let { bait ->
                    if (!Game.baitPicker) Game.baitButton("button")
                    // Picked a moment later, as a hand would (the list drawn first).
                    main.postDelayed({ Game.pickBait(bait, "picker"); Log.i(TAG, "bait now ${Game.trip?.bait?.name?.lowercase()}") }, 400)
                } ?: Log.w(TAG, "unknown bait $b")
            }
            "result" -> Game.debugFinish()
            "netdrop" -> Game.dropNet()
            "hud" -> Log.i(TAG, "hud ${Game.debugHands}")
            "walk" -> { main.removeCallbacksAndMessages(null); walkAt = 0; main.postDelayed(::walkStep, 300) }
            "grant" -> Game.grant(id?.toIntOrNull() ?: 3)
            "turn" -> { Game.debugTurn = id?.toFloatOrNull(); android.util.Log.i(TAG, "turn ${Game.debugTurn}") }
            "look" -> Game.debugLook = id?.toDoubleOrNull()
            "window" -> if (id == "menuoff") Game.menuOpen = false else Game.debugOpen = id
            "reset" -> { main.removeCallbacksAndMessages(null); auto = false; Game.restart() }
            "stop" -> { main.removeCallbacksAndMessages(null); auto = false }
            "state" -> Game.trip?.let { t -> Log.i(TAG, "state ${t.state} casts=${t.casts} castDist=${"%.1f".format(t.castDist)} yaw=${"%.0f".format(t.castYaw)} catches=${t.catches.size} landedBy=${t.landedBy} rod=${Game.rodInHand} net=${Game.netInHand} handsLive=${Game.handsLive} dist=${"%.1f".format(t.distance)} tension=${"%.2f".format(t.tension)} tutorial=${Game.tutorialStep} netHint=${Game.netHint} easy=${t.easy}") }
            "replace" -> { Game.debugZeroHeadS = id?.toDoubleOrNull() ?: 0.0; Game.placeRequest++ }
            "gaze" -> Game.debugGaze = id
            else -> Log.w(TAG, "unknown command $cmd")
        }
    }

    /** Hook `panels`: what is up, where (angles from the eye, as drawn), gaps, overlaps, and what stays in sight. */
    private fun panels() {
        val t = Game.trip
        val st = t?.state
        val lineOut = st == Trip.State.WAIT || st == Trip.State.BITE || st == Trip.State.FIGHT || st == Trip.State.NETTABLE
        val shown = PanelProbe.shown
        Log.i(TAG, "panels phase=${Game.phase} state=$st menu=${Game.menuOpen} tutorial=${Game.tutorialStep} netHint=${Game.netHint} note=${Game.note != null} window=${Game.window ?: "-"}: ${shown.joinToString(" ") { it.id }}")
        for (line in PanelLayout.report(shown, PanelProbe.sizes, PanelLayout.targets(Game.phase.name, lineOut, st == Trip.State.NETTABLE))) Log.i(TAG, line)
        Game.window?.let { Log.i(TAG, "panels: window $it is open (placed by the system, not in the layout)") }
    }

    /** Hook `ui`: the left panel's texts as composed, and the game's values beside them. */
    private fun ui() {
        val ago = if (PanelProbe.hudAt == 0L) -1L else android.os.SystemClock.uptimeMillis() - PanelProbe.hudAt
        Log.i(TAG, "ui hud: ${PanelProbe.hud} (composed ${ago} ms ago, ${PanelProbe.hudCount} times)")
        val t = Game.trip ?: return
        val l = Game.live
        Log.i(TAG, "ui game: state=${t.state} bait=${t.bait.name.lowercase()} canChange=${BaitRules.canChange(t.state)} picker=${Game.baitPicker} tension=${"%.2f".format(t.tension)} strength=${"%.2f".format(Trip.strengthLeft(t))} dist=${"%.1f".format(t.distance)} live=(${l.tension}% ${l.strength}% ${l.distance} m ${l.turns} turns) hint=${BaitRules.hint(t) ?: "-"}")
    }

    /** One decision every 50 ms, as the unit tests' angler. */
    private fun angle() {
        val t = Game.trip ?: return
        if (!auto || Game.phase != Game.Phase.PLAYING) return
        val l = t.lake
        val want = Fish.byId(when (l.id) {
            "xihu" -> if (t.count("crucian") < 3) "crucian" else "bream"
            "qiandao" -> if (!t.catches.any { it.species == "bighead" && it.jin >= 5 }) "bighead" else "culter"
            "lijiang" -> if (t.count("mandarin") < 2) "mandarin" else "catfish"
            "night" -> if (t.count("koi") < 2) "koi" else "crucian"
            "king" -> "king"
            else -> "bighead"
        })
        when (t.state) {
            Trip.State.READY, Trip.State.LANDED -> {
                if (t.bait != want.likes.first()) Game.pickBait(want.likes.first(), "auto")
                // The cast the arm's throw makes (the same Game.castBy), aimed at the rocks when needed.
                val yaw = if (want.rocks && l.rocksAt.isNotEmpty()) l.rocksAt[0].first else 0.0
                val goal = if (want.rocks && l.rocksAt.isNotEmpty()) l.rocksAt[0].second else if (want.far) 13.0 else 6.0
                if (l.ice) Game.press() else Game.castBy((goal - 3.0) / 13.0, yaw)
            }
            Trip.State.BITE -> Game.press()
            Trip.State.FIGHT -> if (t.running <= 0 && t.tension < 0.7) Game.reel(2.4 * 0.05)
            Trip.State.NETTABLE -> Game.net()
            else -> Unit
        }
        main.postDelayed(::angle, 50)
    }

    private fun walkStep() {
        if (walkAt >= Lakes.MAIN.size) {
            val p = Game.progress
            Log.i(TAG, "walk done stars=${p.stars}/${p.maxStars} bronze=${p.bronze} silver=${p.silver} gold=${p.gold}")
            auto = false
            return
        }
        val l = Lakes.MAIN[walkAt++]
        Game.open(l)
        main.postDelayed({ Game.begin(); auto = true; angle(); waitResult(l.id) }, 1500)
    }

    private fun waitResult(id: String) {
        if (Game.phase == Game.Phase.RESULT) {
            val t = Game.trip
            Log.i(TAG, "result $id stars=${t?.stars()} casts=${t?.casts} breaks=${t?.breaks}")
            main.postDelayed(::walkStep, 1500)
        } else main.postDelayed({ waitResult(id) }, 500)
    }
}
