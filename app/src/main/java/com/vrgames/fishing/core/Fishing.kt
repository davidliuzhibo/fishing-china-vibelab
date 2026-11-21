package com.vrgames.fishing.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Bait(val label: String) { WORM("蚯蚓"), CORN("玉米"), DOUGH("面饵"), SPOON("亮片") }

/**
 * A kind of fish: [model] its 3D figure, weights in jin (斤), [strength] how hard it pulls, [stamina]
 * how long it can, [likes] the baits it takes (first is best), [far] whether it keeps to deep water
 * far out (else near the bank), [rocks] whether it hides by rocks (the cast must land near them).
 */
data class Species(
    val id: String,
    val name: String,
    val model: String,
    val minJin: Double,
    val maxJin: Double,
    val strength: Double,
    val stamina: Double,
    val likes: List<Bait>,
    val far: Boolean,
    val rocks: Boolean = false,
    val note: String,
)

object Fish {
    val ALL = listOf(
        Species("crucian", "鲫鱼", "crucian", 0.3, 1.2, 0.35, 5.0, listOf(Bait.WORM, Bait.DOUGH), far = false, note = "最常见的淡水鱼之一，身体侧扁、银灰带金光，喜欢在近岸的水草边觅食。"),
        Species("bream", "鳊鱼", "bream", 0.6, 2.5, 0.45, 6.0, listOf(Bait.DOUGH, Bait.CORN), far = true, note = "身体扁平呈菱形，银白色，湖北的团头鲂就是有名的“武昌鱼”。"),
        Species("bighead", "鳙鱼", "bighead", 3.0, 18.0, 0.75, 12.0, listOf(Bait.DOUGH), far = true, note = "头特别大，所以叫胖头鱼。千岛湖的鳙鱼生长在清水里，以浮游生物为食。"),
        Species("silver", "白鲢", "silver", 2.0, 8.0, 0.65, 9.0, listOf(Bait.DOUGH, Bait.CORN), far = true, note = "银白色，受惊时会成群跃出水面，和鳙鱼一样吃浮游生物。"),
        Species("culter", "翘嘴", "culter", 1.0, 6.0, 0.8, 8.0, listOf(Bait.SPOON), far = true, note = "嘴向上翘，是凶猛的掠食者，爱追逐小鱼，所以要用闪闪发亮的亮片假饵。"),
        Species("mandarin", "鳜鱼", "mandarin", 0.8, 4.0, 0.7, 8.0, listOf(Bait.WORM), far = false, rocks = true, note = "“桃花流水鳜鱼肥”。身上有黑色斑纹，喜欢藏在石头缝里伏击活食。"),
        Species("catfish", "黄颡鱼", "catfish", 0.2, 0.8, 0.4, 5.0, listOf(Bait.WORM), far = false, rocks = true, note = "金黄带黑斑、有胡须，被钓上来时会发出“嘎嘎”的叫声，俗称黄辣丁。"),
        Species("carp", "鲤鱼", "carp", 2.0, 12.0, 0.7, 10.0, listOf(Bait.CORN, Bait.DOUGH), far = false, note = "力气大、耐力足，是最受钓鱼人喜爱的对手之一。"),
        Species("koi", "锦鲤", "koi", 1.0, 6.0, 0.5, 7.0, listOf(Bait.CORN), far = false, note = "红白相间的观赏鲤，月光下鳞片闪着微光。"),
        Species("king", "查干湖头鱼", "bighead", 30.0, 40.0, 1.0, 46.0, listOf(Bait.DOUGH), far = true, note = "查干湖冬捕时，第一网打上来的最大的鱼叫“头鱼”，寓意吉祥。"),
    )
    fun byId(id: String) = ALL.first { it.id == id }
}

/**
 * A goal of a lake: [check] reads the lake's catch log. [fish]: the species it asks for (null: any fish), so the left
 * panel can say which bait it needs (v0.5.1: 「目标里的翘嘴要用亮片」).
 */
class Mission(val text: String, val fish: String? = null, val progress: (Trip) -> String = { "" }, val check: (Trip) -> Boolean)

/**
 * v0.5.2 (device test 2026-11-21: 「已经钓上一条或两条鲫鱼，左边的面板是没有任何变化的……目前是(1/3)」): how far each
 * goal has got, shown after its text on the left panel and in the catch note.
 */
object GoalProgress {
    fun count(n: Int, id: String): (Trip) -> String = { t -> "（${minOf(t.count(id), n)}/$n）" }
    fun any(n: Int): (Trip) -> String = { t -> "（${minOf(t.catches.size, n)}/$n）" }
    fun noBreak(): (Trip) -> String = { t -> if (t.breaks == 0) "（到现在没断过）" else "（断过 ${t.breaks} 次，这一项没做到）" }
    /** One fish of at least [jin] (of [id], or any): 0/1, and the biggest so far if it was too small. */
    fun big(jin: Double, id: String?): (Trip) -> String = { t ->
        val mine = t.catches.filter { id == null || it.species == id }
        val best = mine.maxOfOrNull { it.jin }
        when {
            best != null && best >= jin -> "（1/1）"
            best != null -> "（0/1，最大的 ${Math.round(best * 10) / 10.0} 斤）"
            else -> "（0/1）"
        }
    }
    fun castsAtMost(n: Int, mainDone: Boolean = true): (Trip) -> String = { t ->
        if (mainDone && t.mainAtCasts > 0) "（抛了 ${t.mainAtCasts} 次）" else "（已抛 ${t.casts}/$n 次）"
    }
}

/**
 * A fishing place: its panorama ([sky]), what swims there ([fish], weighted), the rocks to cast at,
 * whether it is an ice hole (no casting), the three missions (the first is the main one).
 */
class Lake(
    val id: String,
    val name: String,
    val place: String,
    val sky: String,
    val story: String,
    val fish: List<Pair<String, Double>>,
    val rocksAt: List<Pair<Double, Double>> = emptyList(),
    val ice: Boolean = false,
    val missions: List<Mission>,
    val hidden: Boolean = false,
) {
    /**
     * v0.5.1: the bait an outing here starts with — the favourite of the main goal's fish (or of the first fish here),
     * so a new lake never starts on a bait nothing there takes (0.5.0 started every lake on worms: at 千岛湖 nothing bit).
     */
    val startBait: Bait get() = Fish.byId(missions.first().fish ?: fish.first().first).likes.first()
}

object Lakes {
    val ALL = listOf(
        Lake("xihu", "杭州西湖", "浙江杭州", "xihu", "春天的苏堤，柳枝拂着水面。先学会抛竿、提竿和收线。",
            listOf("crucian" to 3.0, "bream" to 1.0),
            missions = listOf(
                Mission("钓到 3 条鲫鱼", "crucian", GoalProgress.count(3, "crucian")) { t -> t.count("crucian") >= 3 },
                Mission("钓到 1 条鳊鱼（远处，面饵）", "bream", GoalProgress.count(1, "bream")) { t -> t.count("bream") >= 1 },
                Mission("一次也不断线", progress = GoalProgress.noBreak()) { t -> t.done && t.breaks == 0 },
            )),
        Lake("qiandao", "千岛湖", "浙江淳安", "qiandao", "湖心的小船上，四周是一座座绿岛。大鱼会突然冲刺，线绷紧了就停手放线。",
            listOf("bighead" to 1.5, "silver" to 1.5, "culter" to 1.0),
            missions = listOf(
                Mission("钓到 1 条 5 斤以上的鳙鱼", "bighead", GoalProgress.big(5.0, "bighead")) { t -> t.catches.any { it.species == "bighead" && it.jin >= 5 } },
                Mission("钓到 1 条翘嘴（用亮片）", "culter", GoalProgress.count(1, "culter")) { t -> t.count("culter") >= 1 },
                Mission("一次也不断线", progress = GoalProgress.noBreak()) { t -> t.done && t.breaks == 0 },
            )),
        Lake("lijiang", "桂林漓江", "广西桂林", "lijiang", "傍晚的竹筏上，四面是倒映在江里的山峰。鳜鱼藏在石头边，只吃活饵。",
            listOf("mandarin" to 1.5, "catfish" to 2.0, "carp" to 1.0),
            rocksAt = listOf(-28.0 to 9.0, 22.0 to 7.0),
            missions = listOf(
                Mission("钓到 2 条鳜鱼（往石头边抛，用蚯蚓）", "mandarin", GoalProgress.count(2, "mandarin")) { t -> t.count("mandarin") >= 2 },
                Mission("钓到 1 条黄颡鱼", "catfish", GoalProgress.count(1, "catfish")) { t -> t.count("catfish") >= 1 },
                Mission("抛竿不超过 12 次就完成主线", progress = GoalProgress.castsAtMost(12)) { t -> t.mainAtCasts in 1..12 },
            )),
        Lake("chagan", "查干湖冰钓", "吉林松原", "chagan", "冬天冰封的湖面，在冰洞里下钩。冰下的大鳙鱼力气很大，要放线、收线好几个回合。",
            listOf("bighead" to 2.0, "carp" to 1.0),
            ice = true,
            missions = listOf(
                Mission("钓到 1 条 10 斤以上的大鱼", progress = GoalProgress.big(10.0, null)) { t -> t.catches.any { it.jin >= 10 } },
                Mission("钓到 3 条鱼", progress = GoalProgress.any(3)) { t -> t.catches.size >= 3 },
                Mission("一次也不断线", progress = GoalProgress.noBreak()) { t -> t.done && t.breaks == 0 },
            )),
        Lake("night", "西湖月夜", "银奖杯 · 夜钓", "xihu_night", "月光下的西湖，能钓到会发光的锦鲤。",
            listOf("koi" to 1.0, "crucian" to 1.0),
            missions = listOf(Mission("钓到 2 条锦鲤", "koi", GoalProgress.count(2, "koi")) { t -> t.count("koi") >= 2 }, Mission("钓到 1 条鲫鱼", "crucian", GoalProgress.count(1, "crucian")) { t -> t.count("crucian") >= 1 }, Mission("一次也不断线", progress = GoalProgress.noBreak()) { t -> t.done && t.breaks == 0 }),
            hidden = true),
        Lake("king", "查干湖 · 头鱼", "金奖杯 · 隐藏鱼王", "chagan", "传说中的头鱼，四十斤的大家伙，要拉锯一分多钟。",
            listOf("king" to 1.0), ice = true,
            missions = listOf(Mission("钓上头鱼", "king", GoalProgress.count(1, "king")) { t -> t.count("king") >= 1 }, Mission("不断线", progress = GoalProgress.noBreak()) { t -> t.done && t.breaks == 0 }, Mission("三次以内钓上", progress = GoalProgress.castsAtMost(3, mainDone = false)) { t -> t.done && t.casts <= 3 }),
            hidden = true),
    )
    val MAIN get() = ALL.filter { !it.hidden }
    fun byId(id: String) = ALL.first { it.id == id }
}

data class Catch(val species: String, val jin: Double)

/**
 * One outing at a lake, stepped in slices of at most [STEP] seconds. States: ready to cast; the float out, waiting
 * (nibbles, then a bite); hooked and fighting; landed. Commands: [cast] (from the arm's throw), [press] (strike; on the
 * ice it drops the line into the hole), [reel] (turns of the reel), [net], [reelIn] (wind in to cast again).
 *
 * The fight: the fish pulls with its strength, harder in its runs; reeling pulls back. Line tension
 * is the sum; above 1 the line breaks. Reeling brings the fish in only when it is not running hard;
 * it tires as it pulls. Close and tired, it can be netted — or (v0.5, the net is optional) reeled on up the bank:
 * [REEL_LAND_DIST] lands it.
 *
 * v0.5: the one-button power swing (POWER) is gone: casting is the arm's two-step throw, and with no hand tracking the
 * same throw made by a pinch-and-drag on the rod (device test 2026-11-20: a pinch on the water started the old swing
 * and a second one cast toward where the head pointed). [easyNext] makes the next cast the tutorial's easy crucian.
 */
class Trip(val lake: Lake, seed: Long = lake.id.hashCode().toLong()) {
    enum class State { READY, WAIT, BITE, FIGHT, NETTABLE, LANDED }

    private var rnd = seed xor 0x2545F4914F6CDD1DL
    private fun rand(): Double {
        rnd = rnd xor (rnd shl 13); rnd = rnd xor (rnd ushr 7); rnd = rnd xor (rnd shl 17)
        return ((rnd ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    var state = State.READY; private set
    var bait = lake.startBait
    var aimYaw = 0.0
    var castDist = 0.0; private set
    var castYaw = 0.0; private set
    var waitLeft = 0.0; private set
    var nibbles = 0; private set
    var nibbleT = 0.0; private set
    var biteLeft = 0.0; private set
    var fish: Species? = null; private set
    var jin = 0.0; private set
    var distance = 0.0; private set
    var tension = 0.0; private set
    var stamina = 0.0; private set
    var running = 0.0; private set
    private var runCool = 0.0
    private var reelRate = 0.0
    var landedLast: Catch? = null; private set
    val catches = ArrayList<Catch>()
    var casts = 0; private set
    var breaks = 0; private set
    var spooked = 0; private set
    var time = 0.0; private set
    var mainAtCasts = 0; private set
    val done get() = lake.missions.first().check(this)
    val events = ArrayList<String>()
    /** v0.5: the next cast brings the tutorial's fish (a small crucian: quick to bite, forgives an early strike, no runs). */
    var easyNext = false
    /** The fish on the line (or coming) is the tutorial's easy one. */
    var easy = false; private set

    fun count(id: String) = catches.count { it.species == id }

    /**
     * A pinch at the water or the rod: strikes a bite. Struck at a nibble the fish is spooked ("spooked"; the tutorial's
     * fish only says it was too early: "early"). On the ice, ready, it drops the line into the hole. Before a cast on
     * open water it does nothing ("hand": the rod is cast by the arm).
     */
    fun press(): String {
        events.clear()
        return when (state) {
            State.READY, State.LANDED -> if (lake.ice) castNow(0.3) else "hand"
            State.WAIT -> when {
                nibbleT > 0 && easy -> "early"
                nibbleT > 0 -> { spooked++; waitLeft = 4 + rand() * 4; nibbles = 0; nibbleT = 0.0; "spooked" }
                else -> "nothing"
            }
            State.BITE -> { hook(); "hooked" }
            else -> "nothing"
        }
    }

    /** v0.5: winds the float in while waiting, to cast again (a cast that went somewhere no fish comes is no dead end). */
    fun reelIn(): Boolean {
        if (state != State.WAIT) return false
        state = State.READY
        fish = null
        easy = false
        nibbles = 0
        nibbleT = 0.0
        return true
    }

    /**
     * A cast made by the arm (2026-11-10 review: cast with the hand): pull the rod back, then flick it
     * forward; [p] (0..1) comes from how fast the flick was. On ice the hole is right below.
     */
    fun cast(p: Double): String {
        if (state != State.READY && state != State.LANDED) return "nothing"
        events.clear()
        return castNow(if (lake.ice) 0.3 else p.coerceIn(0.0, 1.0))
    }

    /** The rod lifted sharply: a strike (the same as the one button while waiting or bitten). */
    fun strike(): String = if (state == State.WAIT || state == State.BITE) press() else "nothing"

    private fun castNow(p: Double): String {
        casts++
        castDist = if (lake.ice) 0.0 else 3.0 + 13.0 * p
        castYaw = aimYaw
        state = State.WAIT
        nibbles = 0
        nibbleT = 0.0
        // Who comes: fish that like this bait and this place; the wait is shorter for a good match.
        val cands = lake.fish.mapNotNull { (id, w) ->
            val s = Fish.byId(id)
            val baitOk = bait in s.likes
            val placeOk = lake.ice || s.far == (castDist > 9) || s.rocks && nearRocks()
            val rockOk = !s.rocks || lake.ice || nearRocks()
            if (!baitOk || !rockOk) null else id to w * (if (placeOk) 1.0 else 0.25) * (if (s.likes.first() == bait) 1.5 else 1.0)
        }
        fish = if (cands.isEmpty()) null else pick(cands)
        waitLeft = if (fish == null) 999.0 else 3 + rand() * 5
        easy = easyNext
        easyNext = false
        if (easy) { fish = Fish.byId("crucian"); waitLeft = EASY_WAIT + rand() }
        return "cast"
    }

    private fun nearRocks() = lake.rocksAt.any { (yaw, dist) -> abs(yaw - castYaw) < 12 && abs(dist - castDist) < 3 }

    private fun pick(c: List<Pair<String, Double>>): Species {
        var r = rand() * c.sumOf { it.second }
        for ((id, w) in c) { r -= w; if (r <= 0) return Fish.byId(id) }
        return Fish.byId(c.last().first)
    }

    private fun hook() {
        val s = fish ?: return
        jin = s.minJin + (s.maxJin - s.minJin) * (rand() * rand() + rand() * 0.3).coerceAtMost(1.0)
        distance = max(castDist, 4.0)
        tension = 0.3
        stamina = s.stamina * (0.7 + jin / s.maxJin * 0.6)
        running = 0.0
        runCool = 2.0
        if (easy) { jin = 0.5 + rand() * 0.2; stamina = s.stamina * NET_TIRED * 0.9; runCool = Double.MAX_VALUE }
        // v0.4: turns wound while waiting were kept and pulled all at once on the first fight step, snapping the line.
        reelRate = 0.0
        state = State.FIGHT
    }

    /**
     * Turns of the reel since the last call (from the hand's circles, or taps of a quarter turn). Only while a fish is
     * on (v0.4: before, turns wound while waiting piled up and broke the line the moment the fish was hooked).
     */
    fun reel(turns: Double) {
        if (state != State.FIGHT && state != State.NETTABLE) return
        reelRate += turns
    }

    fun net(): Boolean {
        if (state != State.NETTABLE) return false
        land("net")
        return true
    }

    fun step(dt0: Double) {
        // The turns reeled since the last step are spread over its sub-steps: a long frame (a hitch, or the emulator's
        // seconds-long frames) must not pour all of them into the first 0.05 s and read as a burst of winding (tension).
        val reeled = reelRate
        var left = dt0
        while (left > 1e-9) {
            val dt = min(left, STEP)
            reelRate = if (dt0 > 0) reeled * dt / dt0 else reeled
            tick(dt)
            left -= dt
        }
        reelRate = 0.0
    }

    private fun tick(dt: Double) {
        time += dt
        when (state) {
            State.WAIT -> {
                if (nibbleT > 0) nibbleT -= dt
                waitLeft -= dt
                if (waitLeft <= 0) {
                    // A nibble or two first (do not strike!), then the bite. The tutorial's fish: one nibble, then the bite.
                    if (easy && nibbles < 1) {
                        nibbles++
                        nibbleT = 0.6
                        waitLeft = 1.6
                        events += "nibble"
                    } else if (!easy && nibbles < 2 && rand() < 0.6) {
                        nibbles++
                        nibbleT = 0.5
                        waitLeft = 1.0 + rand() * 1.5
                        events += "nibble"
                    } else {
                        state = State.BITE
                        biteLeft = BITE_WINDOW
                        events += "bite"
                    }
                }
            }
            State.BITE -> {
                val before = biteLeft
                biteLeft -= dt
                // Still on: the float stays down and the player is reminded now and then.
                if (biteLeft > 0 && kotlin.math.floor((BITE_WINDOW - biteLeft) / BITE_REMIND) > kotlin.math.floor((BITE_WINDOW - before) / BITE_REMIND)) events += "biting"
                if (biteLeft <= 0) {
                    state = State.WAIT
                    waitLeft = 3 + rand() * 4
                    nibbles = 0
                    events += "missed"
                }
            }
            State.FIGHT, State.NETTABLE -> fight(dt)
            else -> Unit
        }
    }

    private fun fight(dt: Double) {
        val s = fish ?: return
        val rate = reelRate / dt
        reelRate = 0.0
        // Runs: now and then, while it has strength left. A fish tired out at the bank (NETTABLE) makes no more runs:
        // the player lets go of the reel to take the net, and the net must not turn back into a ghost under the hand.
        runCool -= dt
        if (running > 0) running -= dt
        else if (runCool <= 0 && stamina > 0.5 && state != State.NETTABLE) {
            running = 1.2 + rand() * 1.5
            runCool = 2.0 + rand() * 3.0
            events += "run"
        }
        val tired = (stamina / (s.stamina + 1e-9)).coerceIn(0.0, 1.0)
        val pull = s.strength * (0.35 + 0.65 * tired) * (if (running > 0) 1.9 else 1.0) * (0.7 + 0.3 * jin / s.maxJin)
        // Even the biggest fish's run alone stays below breaking: only reeling into it breaks the line.
        val target = pull * 0.45 + rate * REEL_TENSION
        tension += (target - tension) * min(1.0, dt * 6)
        if (tension > 1.0) {
            breaks++
            state = State.READY
            fish = null
            easy = false
            events += "break"
            return
        }
        // Distance: the fish swims off while running; reeling brings it in (less while it pulls). A fish with strength
        // left keeps out of the net's reach; tired at the bank it is hauled in slowly (v0.5: up the bank, no net needed).
        val nettable = state == State.NETTABLE
        distance += (if (running > 0) 1.6 * s.strength else 0.0) * dt
        distance -= rate * REEL_METERS * (1.0 - 0.6 * (if (running > 0) 1.0 else 0.0)) * (if (nettable) NETTABLE_REEL else 1.0) * dt
        distance = distance.coerceIn(if (nettable) 0.5 else FIGHT_MIN_DIST, 30.0)
        stamina -= dt * (0.4 + tension)
        if (nettable && rate > 0 && distance <= REEL_LAND_DIST) { land("reel"); return }
        if (distance <= NET_DIST && stamina < s.stamina * NET_TIRED) {
            if (state != State.NETTABLE) events += "nettable"
            state = State.NETTABLE
        } else if (state == State.NETTABLE && distance > NET_DIST + 0.5) { state = State.FIGHT; events += "away" }
    }

    private fun land(how: String) {
        val c = Catch(fish!!.id, Math.round(jin * 10) / 10.0)
        catches += c
        landedLast = c
        state = State.LANDED
        easy = false
        if (mainAtCasts == 0 && done) mainAtCasts = casts
        events += "landed"
        landedBy = how
    }

    /** How the last fish came in: "net" or "reel". */
    var landedBy = ""; private set

    /** Debug builds and tests: a fish on the line now, fighting (or tired at the bank, [nettable]) or biting. */
    fun debugState(to: State) {
        if (fish == null) fish = Fish.byId(lake.fish.first().first)
        if (castDist < 4) { castDist = 8.0; casts++ }
        when (to) {
            State.BITE -> { state = State.BITE; biteLeft = BITE_WINDOW }
            State.FIGHT -> { hook(); state = State.FIGHT; runCool = 3.0 }
            State.NETTABLE -> { hook(); distance = NET_DIST - 0.3; stamina = fish!!.stamina * NET_TIRED * 0.5; state = State.NETTABLE; events += "nettable" }
            else -> Unit
        }
    }

    /** Stars: one per mission met (the first is needed to finish). */
    fun stars() = if (!done) 0 else lake.missions.count { it.check(this) }

    companion object {
        const val STEP = 0.05
        /**
         * How long a real bite holds the float down (2026-11-13 device test: 0.6 s was gone before anyone could react, and
         * the take looped nibble, bite, "missed" for ever). Ten seconds, with a reminder every few seconds.
         */
        const val BITE_WINDOW = 10.0
        const val BITE_REMIND = 3.0
        /** Tension added per turn a second of reeling, and meters brought in per turn. */
        const val REEL_TENSION = 0.16
        const val REEL_METERS = 1.2
        const val NET_DIST = 1.8
        /** v0.5: a fish with strength left is kept this far out (only a tired one comes to the net). */
        const val FIGHT_MIN_DIST = 1.4
        /** v0.5: reeling a tired fish at the bank brings it in at this share of the usual pace, and lands it here (no net). */
        const val NETTABLE_REEL = 0.45
        const val REEL_LAND_DIST = 0.6
        /** The tutorial's fish: seconds before its nibble. */
        const val EASY_WAIT = 2.0
        /**
         * The fish can be netted once its strength is below this share (was 0.35; 2026-11-13 device test: a fish a metre
         * off still could not be netted, and nothing said why).
         */
        const val NET_TIRED = 0.6

        /** How much strength the hooked fish has left, 0..1. */
        fun strengthLeft(t: Trip): Double = t.fish?.let { (t.stamina / (it.stamina + 1e-9)).coerceIn(0.0, 1.0) } ?: 0.0
    }
}

/** Best stars per lake, records, and the trophies. */
class Progress {
    val best = LinkedHashMap<String, Int>()
    val record = LinkedHashMap<String, Double>()
    fun record(lake: String, stars: Int) { if (stars > (best[lake] ?: 0)) best[lake] = stars }
    fun catchOf(c: Catch) { if (c.jin > (record[c.species] ?: 0.0)) record[c.species] = c.jin }
    val stars get() = Lakes.MAIN.sumOf { best[it.id] ?: 0 }
    val maxStars get() = Lakes.MAIN.size * 3
    val bronze get() = Lakes.MAIN.all { (best[it.id] ?: 0) > 0 }
    val silver get() = bronze && stars * 2 >= maxStars
    val gold get() = bronze && stars == maxStars
    fun open(l: Lake) = when (l.id) {
        "night" -> silver
        "king" -> gold
        else -> Lakes.MAIN.indexOf(l) <= Lakes.MAIN.indexOfFirst { (best[it.id] ?: 0) == 0 }.let { if (it < 0) Lakes.MAIN.size else it }
    }
    fun save() = best.entries.joinToString(",") { "${it.key}:${it.value}" } + ";" + record.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        fun load(s: String?) = Progress().apply {
            val (b, r) = (s ?: ";").split(";").let { it[0] to it.getOrElse(1) { "" } }
            b.split(",").forEach { e -> e.split(":").takeIf { it.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { best[k] = it.coerceIn(0, 3) } } }
            r.split(",").forEach { e -> e.split(":").takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { record[k] = it } } }
        }
    }
}
