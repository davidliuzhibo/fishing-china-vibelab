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

/** A goal of a lake: [check] reads the lake's catch log. */
class Mission(val text: String, val check: (Trip) -> Boolean)

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
)

object Lakes {
    val ALL = listOf(
        Lake("xihu", "杭州西湖", "浙江杭州", "xihu", "春天的苏堤，柳枝拂着水面。先学会抛竿、提竿和收线。",
            listOf("crucian" to 3.0, "bream" to 1.0),
            missions = listOf(
                Mission("钓到 3 条鲫鱼") { t -> t.count("crucian") >= 3 },
                Mission("钓到 1 条鳊鱼（远处，面饵）") { t -> t.count("bream") >= 1 },
                Mission("一次也不断线") { t -> t.done && t.breaks == 0 },
            )),
        Lake("qiandao", "千岛湖", "浙江淳安", "qiandao", "湖心的小船上，四周是一座座绿岛。大鱼会突然冲刺，线绷紧了就停手放线。",
            listOf("bighead" to 1.5, "silver" to 1.5, "culter" to 1.0),
            missions = listOf(
                Mission("钓到 1 条 5 斤以上的鳙鱼") { t -> t.catches.any { it.species == "bighead" && it.jin >= 5 } },
                Mission("钓到 1 条翘嘴（用亮片）") { t -> t.count("culter") >= 1 },
                Mission("一次也不断线") { t -> t.done && t.breaks == 0 },
            )),
        Lake("lijiang", "桂林漓江", "广西桂林", "lijiang", "傍晚的竹筏上，四面是倒映在江里的山峰。鳜鱼藏在石头边，只吃活饵。",
            listOf("mandarin" to 1.5, "catfish" to 2.0, "carp" to 1.0),
            rocksAt = listOf(-28.0 to 9.0, 22.0 to 7.0),
            missions = listOf(
                Mission("钓到 2 条鳜鱼（往石头边抛，用蚯蚓）") { t -> t.count("mandarin") >= 2 },
                Mission("钓到 1 条黄颡鱼") { t -> t.count("catfish") >= 1 },
                Mission("抛竿不超过 12 次就完成主线") { t -> t.mainAtCasts in 1..12 },
            )),
        Lake("chagan", "查干湖冰钓", "吉林松原", "chagan", "冬天冰封的湖面，在冰洞里下钩。冰下的大鳙鱼力气很大，要放线、收线好几个回合。",
            listOf("bighead" to 2.0, "carp" to 1.0),
            ice = true,
            missions = listOf(
                Mission("钓到 1 条 10 斤以上的大鱼") { t -> t.catches.any { it.jin >= 10 } },
                Mission("钓到 3 条鱼") { t -> t.catches.size >= 3 },
                Mission("一次也不断线") { t -> t.done && t.breaks == 0 },
            )),
        Lake("night", "西湖月夜", "银奖杯 · 夜钓", "xihu_night", "月光下的西湖，能钓到会发光的锦鲤。",
            listOf("koi" to 1.0, "crucian" to 1.0),
            missions = listOf(Mission("钓到 2 条锦鲤") { t -> t.count("koi") >= 2 }, Mission("钓到 1 条鲫鱼") { t -> t.count("crucian") >= 1 }, Mission("一次也不断线") { t -> t.done && t.breaks == 0 }),
            hidden = true),
        Lake("king", "查干湖 · 头鱼", "金奖杯 · 隐藏鱼王", "chagan", "传说中的头鱼，四十斤的大家伙，要拉锯一分多钟。",
            listOf("king" to 1.0), ice = true,
            missions = listOf(Mission("钓上头鱼") { t -> t.count("king") >= 1 }, Mission("不断线") { t -> t.done && t.breaks == 0 }, Mission("三次以内钓上") { t -> t.done && t.casts <= 3 }),
            hidden = true),
    )
    val MAIN get() = ALL.filter { !it.hidden }
    fun byId(id: String) = ALL.first { it.id == id }
}

data class Catch(val species: String, val jin: Double)

/**
 * One outing at a lake, stepped in slices of at most [STEP] seconds. States: ready to cast; the power
 * swinging; the float out, waiting (nibbles, then a bite); hooked and fighting; landed. Commands:
 * [press] (start the power swing, cast, strike on a bite), [reel] (turns of the reel), [net].
 *
 * The fight: the fish pulls with its strength, harder in its runs; reeling pulls back. Line tension
 * is the sum; above 1 the line breaks. Reeling brings the fish in only when it is not running hard;
 * it tires as it pulls. Close and tired, it can be netted.
 */
class Trip(val lake: Lake, seed: Long = lake.id.hashCode().toLong()) {
    enum class State { READY, POWER, WAIT, BITE, FIGHT, NETTABLE, LANDED }

    private var rnd = seed xor 0x2545F4914F6CDD1DL
    private fun rand(): Double {
        rnd = rnd xor (rnd shl 13); rnd = rnd xor (rnd ushr 7); rnd = rnd xor (rnd shl 17)
        return ((rnd ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    var state = State.READY; private set
    var bait = Bait.WORM
    var aimYaw = 0.0
    var power = 0.0; private set
    var powerT = 0.0
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

    fun count(id: String) = catches.count { it.species == id }

    /** The one button: start the swing, cast, or strike. Returns what happened. */
    fun press(): String {
        events.clear()
        return when (state) {
            State.READY, State.LANDED -> if (lake.ice) castNow(0.3) else { state = State.POWER; powerT = 0.0; "power" }
            State.POWER -> castNow(power)
            State.WAIT -> {
                // Struck at a nibble: the fish is spooked.
                if (nibbleT > 0) { spooked++; waitLeft = 4 + rand() * 4; nibbles = 0; nibbleT = 0.0; "spooked" } else "nothing"
            }
            State.BITE -> { hook(); "hooked" }
            else -> "nothing"
        }
    }

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
        state = State.FIGHT
    }

    /** Turns of the reel since the last call (from the hand's circles, or taps of a quarter turn). */
    fun reel(turns: Double) {
        reelRate += turns
    }

    fun net(): Boolean {
        if (state != State.NETTABLE) return false
        val c = Catch(fish!!.id, Math.round(jin * 10) / 10.0)
        catches += c
        landedLast = c
        state = State.LANDED
        if (mainAtCasts == 0 && done) mainAtCasts = casts
        events += "landed"
        return true
    }

    fun step(dt0: Double) {
        var left = dt0
        while (left > 1e-9) {
            val dt = min(left, STEP)
            tick(dt)
            left -= dt
        }
    }

    private fun tick(dt: Double) {
        time += dt
        when (state) {
            State.POWER -> {
                powerT += dt
                power = 0.5 - 0.5 * kotlin.math.cos(powerT * 2 * Math.PI / POWER_PERIOD)
            }
            State.WAIT -> {
                if (nibbleT > 0) nibbleT -= dt
                waitLeft -= dt
                if (waitLeft <= 0) {
                    // A nibble or two first (do not strike!), then the bite.
                    if (nibbles < 2 && rand() < 0.6) {
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
                biteLeft -= dt
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
        // Runs: now and then, while it has strength left.
        runCool -= dt
        if (running > 0) running -= dt
        else if (runCool <= 0 && stamina > 0.5) {
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
            events += "break"
            return
        }
        // Distance: the fish swims off while running; reeling brings it in (less while it pulls).
        distance += (if (running > 0) 1.6 * s.strength else 0.0) * dt
        distance -= rate * REEL_METERS * (1.0 - 0.6 * (if (running > 0) 1.0 else 0.0)) * dt
        distance = distance.coerceIn(0.5, 30.0)
        stamina -= dt * (0.4 + tension)
        if (distance <= NET_DIST && stamina < s.stamina * 0.35) {
            if (state != State.NETTABLE) events += "nettable"
            state = State.NETTABLE
        } else if (state == State.NETTABLE && distance > NET_DIST + 0.5) state = State.FIGHT
    }

    /** Stars: one per mission met (the first is needed to finish). */
    fun stars() = if (!done) 0 else lake.missions.count { it.check(this) }

    companion object {
        const val STEP = 0.05
        const val POWER_PERIOD = 1.4
        const val BITE_WINDOW = 0.6
        /** Tension added per turn a second of reeling, and meters brought in per turn. */
        const val REEL_TENSION = 0.16
        const val REEL_METERS = 0.9
        const val NET_DIST = 1.8
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
