package com.vrgames.fishing.core

/**
 * v0.5.1 (device test 2026-11-21: 「鱼饵：这个到底应该怎么用？……至少需要在新手教程里讲到……要在每一关的左侧面板里，随时有
 * 一个一直存在的按钮去做相应的切换」): when the bait may be changed, why not, and what to say about it.
 *
 * How bait works (unchanged since 0.1): four baits, all of them always to hand (none is locked); each fish takes only
 * the baits it likes ([Species.likes], the first is its favourite); at a cast only fish that like the bait on the hook
 * can come (a favourite comes sooner), so a bait nothing here takes means nothing ever bites. The bait goes on the hook
 * before the cast: from the cast until the fish is landed or lost, it cannot be changed.
 */
object BaitRules {
    /** The line is in (before a cast, after a catch, a break, or winding in): the bait can be changed. */
    fun canChange(s: Trip.State) = s == Trip.State.READY || s == Trip.State.LANDED

    const val LOCKED = "线已抛出，收回后才能换饵"
    const val LOCKED_WAIT = "线已抛出，点「收竿重抛」收回后才能换饵"

    /** Why the bait cannot be changed now (null: it can). */
    fun lockedWhy(s: Trip.State): String? = when {
        canChange(s) -> null
        s == Trip.State.WAIT -> LOCKED_WAIT
        else -> LOCKED
    }

    /** The fish of [lake] that take [b], its fans first (fish that like it best). */
    fun fansHere(lake: Lake, b: Bait): List<Species> =
        lake.fish.map { Fish.byId(it.first) }.filter { b in it.likes }.sortedBy { it.likes.indexOf(b) }

    /** The baits the fish of [lake] take, in the order of the bait list. */
    fun takenHere(lake: Lake): List<Bait> = Bait.entries.filter { b -> fansHere(lake, b).isNotEmpty() }

    /** The goal fish (open goals of [t]'s lake) this bait is for. */
    private fun goalFish(t: Trip): List<Species> =
        t.lake.missions.filter { !it.check(t) }.mapNotNull { it.fish }.distinct().map { Fish.byId(it) }

    /** One line for a bait in the picker: who takes it here (goal fish marked), or that nothing here does. */
    fun line(t: Trip, b: Bait): String {
        val fans = fansHere(t.lake, b)
        if (fans.isEmpty()) return "这里的鱼都不吃"
        val goals = goalFish(t).map { it.id }.toSet()
        return fans.joinToString("、") { it.name + if (it.id in goals) "（目标）" else "" } + "爱吃"
    }

    /**
     * A gentle hint when the bait on the hook cannot catch what this lake asks for (null: it can): nothing here takes
     * it at all, or an open goal's fish does not («目标里的翘嘴要用亮片»).
     */
    fun hint(t: Trip): String? {
        val here = takenHere(t.lake)
        if (t.bait !in here) return "这里的鱼不吃${t.bait.label}，换成${orList(here.map { it.label })}"
        val miss = goalFish(t).firstOrNull { t.bait !in it.likes } ?: return null
        return "目标里的${miss.name}要用${orList(miss.likes.map { it.label })}"
    }

    /** 「玉米、面饵或亮片」. */
    private fun orList(xs: List<String>) = if (xs.size <= 1) xs.joinToString("") else xs.dropLast(1).joinToString("、") + "或" + xs.last()
}
