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
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.Trip
import kotlin.math.abs

/**
 * Test hook, debug builds only: fishes without hands (a careful angler: the right bait and spot,
 * reeling only while the fish is calm). Results go to logcat (tag FishTest).
 *
 *   adb shell am broadcast -a com.vrgames.fishing.TEST -p com.vrgames.fishing --es cmd walk
 *
 * cmd: start · open --es id <lake> · begin · auto (fish this lake until done) · walk (every lake) ·
 * grant --es id <stars> · look --es id <yaw> · window --es id log|map|menu|close · reset · stop
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
            override fun onReceive(c: Context, i: Intent) = command(i.getStringExtra("cmd") ?: "", i.getStringExtra("id"))
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
            "walk" -> { main.removeCallbacksAndMessages(null); walkAt = 0; main.postDelayed(::walkStep, 300) }
            "grant" -> Game.grant(id?.toIntOrNull() ?: 3)
            "look" -> Game.debugLook = id?.toDoubleOrNull()
            "window" -> Game.debugOpen = id
            "reset" -> { main.removeCallbacksAndMessages(null); auto = false; Game.restart() }
            "stop" -> { main.removeCallbacksAndMessages(null); auto = false }
            else -> Log.w(TAG, "unknown command $cmd")
        }
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
                while (t.bait != want.likes.first()) Game.cycleBait()
                Game.aimYaw = if (want.rocks && l.rocksAt.isNotEmpty()) l.rocksAt[0].first else 0.0
                Game.press()
            }
            Trip.State.POWER -> {
                val goal = if (want.rocks && l.rocksAt.isNotEmpty()) l.rocksAt[0].second else if (want.far) 13.0 else 6.0
                if (abs(t.power - (goal - 3.0) / 13.0) < 0.04) Game.press()
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
