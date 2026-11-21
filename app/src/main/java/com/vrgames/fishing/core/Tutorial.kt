package com.vrgames.fishing.core

/**
 * The step-by-step tutorial from the very first cast (v0.5; device test 2026-11-20: 「不知道怎么拿竿……希望从一开始就有
 * 一步一步的教学，文字加语音」). Each step moves on ONLY when the game reports the real thing happened (the rod in a hand,
 * the 「咔」 of the cock, the line out, …), never on a timer. Setbacks send it back to the step that is needed again (a
 * line broken: take the rod again). The texts say exactly what the code accepts.
 *
 * v0.5.1 (device test 2026-11-21: 「鱼饵……我们在新手教程里并没有提到」): a first step about the bait, eight steps in all.
 * It moves on when a bait is picked with the left panel's 「换饵」 (any bait, the same one too) or on 「知道了」; a player
 * who goes straight for the rod has seen it, and goes on to the step after taking the rod.
 */
class Tutorial {
    enum class Ev { BAIT, ROD_TAKEN, COCKED, CAST, FLOAT_LANDED, BITE, HOOKED, NETTABLE, FISH_LANDED, BREAK, MISSED, AWAY, ROD_LOST, FAIL_SLOW, FAIL_LOST, EARLY }

    class Step(val title: String, val text: String, val voice: String)
    class Hint(val text: String, val voice: String?)

    /** 0: off; 1..8 the steps; 9 done. */
    var step = 0; private set
    /** A setback's line under the step (shown and spoken), cleared when the step moves on. */
    var hint: Hint? = null; private set
    val active get() = step in 1..TOTAL + 1

    fun start() { step = 1; hint = null }
    fun stop() { step = 0; hint = null }

    /** A real event from the game; true when the step or the hint changed. */
    fun on(e: Ev): Boolean {
        if (!active) return false
        val before = step
        val beforeHint = hint
        when (e) {
            Ev.BAIT -> if (step == 1) go(2)
            Ev.ROD_TAKEN -> if (step == 1) go(3) else if (step == 2) go(3)
            Ev.COCKED -> if (step == 3) go(4)
            Ev.CAST -> Unit
            Ev.FLOAT_LANDED -> if (step == 4) go(5)
            Ev.BITE -> if (step == 5) go(6)
            Ev.HOOKED -> if (step == 5 || step == 6) go(7)
            Ev.NETTABLE -> if (step == 7) go(8)
            Ev.FISH_LANDED -> if (step == 7 || step == 8) go(9)
            Ev.AWAY -> if (step == 8) go(7)
            Ev.BREAK -> if (step in 5..8) { go(2); hint = Hint(SNAPPED, "snapped") }
            Ev.MISSED -> if (step == 6) { go(5); hint = Hint(MISSED, "missed") }
            Ev.ROD_LOST -> if (step == 3 || step == 4) { go(2); hint = Hint(LOST_ROD, null) }
            Ev.FAIL_SLOW -> if (step == 3 || step == 4) { go(3); hint = Hint(FAIL_SLOW, "fail_slow") }
            Ev.FAIL_LOST -> if (step == 3 || step == 4) { go(3); hint = Hint(FAIL_LOST, "fail_lost") }
            Ev.EARLY -> if (step == 5 || step == 6) hint = Hint(FAIL_EARLY, "fail_early")
        }
        return step != before || hint !== beforeHint
    }

    /** The extra reeling help after 8 s without winding (the reeling step), once. */
    fun reelHelp(): Boolean {
        if (step != REEL || hint != null) return false
        hint = Hint(REEL_HELP, "tut6b")
        return true
    }

    private fun go(s: Int) { step = s; hint = null }

    val current: Step? get() = STEPS.getOrNull(step - 1)

    /** 「第 1 步／共 8 步 · 选鱼饵」, or 「完成」. */
    val header: String get() = if (step in 1..TOTAL) "第 $step 步／共 $TOTAL 步 · ${STEPS[step - 1].title}" else if (step == TOTAL + 1) "完成" else ""

    companion object {
        const val TOTAL = 8
        /** The bait step (left panel's 「换饵」 or 「知道了」). */
        const val BAIT = 1
        /** The steps while the float or the fish is out on the water (the board goes up, clear of it). */
        val OUT = 5..7
        /** The reeling step (its extra help after [REEL_HELP_S] s without a turn). */
        const val REEL = 7
        /** Casts at these steps bring the tutorial's easy fish. */
        val EASY = 1..4

        /** The tutorial's event for a [Trip] event ("bite", "nettable", "landed", …), or null. */
        fun ofTripEvent(e: String): Ev? = when (e) {
            "bite" -> Ev.BITE
            "nettable" -> Ev.NETTABLE
            "landed" -> Ev.FISH_LANDED
            "break" -> Ev.BREAK
            "missed" -> Ev.MISSED
            "away" -> Ev.AWAY
            else -> null
        }

        val STEPS = listOf(
            Step("选鱼饵", "先看鱼饵：左边面板上写着现在挂的饵，点「换饵」可以换。不同的鱼爱吃不同的饵：关卡目标里写着，比如翘嘴要用亮片。换好了，或者点「知道了」，就去拿鱼竿。", "tut0"),
            Step("拿起鱼竿", "右前方竿架上的竿柄在发金光。伸出右手，像握手一样把竿柄整个握住；也可以眼睛看着竿柄，捏一下手指。拿起来以后不用一直捏着。", "tut1"),
            Step("往后举竿", "把拿竿的手抬到肩膀上方，再往后带一点，好像把鱼竿扛到肩后。听到「咔」的一声，就蓄好力了。", "tut2"),
            Step("向前甩竿", "往前用力一甩，像扔飞镖一样，手停在胸前。甩得越快抛得越远；想抛向左边或右边，就朝那边甩。", "tut3"),
            Step("等鱼咬钩", "浮漂落水了，鱼竿自己回到竿架，手可以放下休息。看着浮漂：轻轻点一下是鱼在试探，别动；整个沉下去才是真咬钩。", "tut4"),
            Step("提竿", "沉下去了！快提竿：手放到竿柄旁边往上一抬，或者看着浮漂捏一下手指。10 秒内都来得及。", "tut5"),
            Step("收线", "中鱼了！用任意一只手在身前画圈，像摇自行车脚踏那样一圈一圈地摇，线就收回来了（不用捏，也不用够到绕线轮）。线变红、鱼往外冲时先停手，不然会断线。", "tut6"),
            Step("抄网（可选）", "鱼拉到岸边了！左前方的抄网亮了：看着它捏一下手指，或者伸左手握住握把，把网伸到鱼下面，往下一压、再往上一兜。不想用抄网，就继续画圈收线，也能把鱼拉上岸。", "tut7"),
            Step("完成", "钓上来了！一整套就是：选饵 → 拿竿 → 往后举 → 向前甩 → 等鱼 → 提竿 → 收线 →（可选）抄网。想再学一遍，随时在菜单里点「新手教学」。", "tut8"),
        )

        /** The net's step, also the hint whenever the net can be used outside the tutorial. */
        val NET = STEPS[7]

        const val FAIL_SLOW = "甩得太慢，没抛出去。举到肩后，再快一点往前甩。"
        const val FAIL_LOST = "手离开了视野，这一下不算，再举一次。"
        const val FAIL_EARLY = "太早了，那只是试探。等浮漂整个沉下去再提竿。"
        const val REEL_HELP = "画圈不灵时，也可以看着绕线轮捏住手指转圈，或者点左边面板上的「收线」。"
        const val MISSED = "等得太久，鱼吐钩跑了。别着急，再等下一条。"
        const val SNAPPED = "线断了！鱼往外冲、线变红的时候要先停手。再把鱼竿拿起来。"
        const val LOST_ROD = "看不到你的手了，鱼竿先放回竿架。再拿起来就行。"
        /** Seconds at step 6 without winding before [REEL_HELP]. */
        const val REEL_HELP_S = 8.0
    }
}
