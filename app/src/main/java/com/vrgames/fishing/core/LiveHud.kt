package com.vrgames.fishing.core

import kotlin.math.roundToInt

/**
 * v0.5.1 (device test 2026-11-21: 「左侧面板里，鱼的力气和距离，不是实时更新的」): everything the left panel shows that
 * can change, rounded the way it is shown. The game keeps one of these in a Compose state and makes a new one every
 * frame (at most 10 a second): the panel reads it, so it redraws as soon as a shown number changes — and only then
 * (two equal snapshots are equal, so Compose does nothing).
 *
 * 0.5.0's panel read the outing's plain fields under a Compose state (`Game.version`) that changed only on events
 * (a bite, a run, a catch); its content was a lambda Compose skipped while the outing object stayed the same. In debug
 * builds the hands' debug line (a state, read inside it) happened to redraw it 4 times a second, so the emulator showed
 * live numbers and the headset (release build, no debug line) did not.
 */
data class LiveHud(
    /** [com.vrgames.fishing.game.Game]'s change counter (events, commands). */
    val ver: Int = 0,
    val state: Trip.State? = null,
    val bait: Bait? = null,
    val casts: Int = 0,
    val breaks: Int = 0,
    val catches: Int = 0,
    /** Line tension, %. */
    val tension: Int = 0,
    /** The hooked fish's strength left, %. */
    val strength: Int = 0,
    /** Distance to the fish, in tenths of a metre. */
    val distDm: Int = 0,
    val running: Boolean = false,
    /** Turns of the reel since the fish was hooked. */
    val turns: Int = 0,
    /** Bit k: mission k met. */
    val met: Int = 0,
    /** The bait can be changed now. */
    val baitOk: Boolean = false,
    /** The bait picker is open. */
    val picker: Boolean = false,
    /** v0.5.2: each goal's progress as shown after its text, e.g. 「（1/3）」. */
    val progress: List<String> = emptyList(),
) {
    val distance: Double get() = distDm / 10.0
    val fighting: Boolean get() = state == Trip.State.FIGHT || state == Trip.State.NETTABLE

    companion object {
        /** The snapshot of [t] now ([ver] the game's counter, [turns] wound since the hook, [canPick] the screen allows a bait change). */
        fun of(t: Trip?, ver: Int, turns: Double, picker: Boolean, canPick: Boolean): LiveHud {
            if (t == null) return LiveHud(ver = ver)
            val fighting = t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE
            var met = 0
            t.lake.missions.forEachIndexed { k, m -> if (m.check(t)) met = met or (1 shl k) }
            val ok = canPick && BaitRules.canChange(t.state)
            return LiveHud(
                ver = ver,
                state = t.state,
                bait = t.bait,
                casts = t.casts,
                breaks = t.breaks,
                catches = t.catches.size,
                tension = if (fighting) (t.tension * 100).roundToInt() else 0,
                strength = if (fighting) (Trip.strengthLeft(t) * 100).roundToInt() else 0,
                distDm = if (fighting) (t.distance * 10).roundToInt() else 0,
                running = fighting && t.running > 0,
                turns = if (fighting) turns.toInt() else 0,
                met = met,
                baitOk = ok,
                picker = picker && ok,
                progress = t.lake.missions.map { it.progress(t) },
            )
        }
    }
}
