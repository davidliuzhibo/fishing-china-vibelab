package com.vrgames.fishing.core

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.5.1 (device test 2026-11-21, 截图「钓鱼1」: the left info panel was drawn over the open menu — 「左侧两个面板互相有遮挡，
 * 类似的情况，你在整个的游戏里也都看一下」): every panel of the stage in one place, as seen from the seat's eye, and which of
 * them can be up together. The stage places them from here ([Slot.at], [Slot.eulerPitch], [Slot.eulerYaw]) and shows
 * exactly [shown]; the tests project each one to angles from the eye and check that no two that can be up together come
 * within [MIN_GAP] degrees of each other, and that none hides the rod, the net, the bait box, the water where the float
 * lands, or the fish at the bank.
 *
 * The panels (scene frame: the eye at [EYE], the scene placed from the measured eyes, core.EyeHeight):
 * - in front, a little left of straight ahead (the rod rests on the right and rises through the middle; 0.5.0's panels
 *   straight ahead had it running through their right side): the lake card [CARD_SLOT], the lake's card [INTRO_SLOT]
 *   and the results with the trophies [RESULT_SLOT] (one at a time, by phase); the tutorial board, which is also the
 *   net's how-to [TUTOR_LOW] (lifted to [TUTOR_HIGH] while the float or the fish is out there); the short note line
 *   [NOTE_SLOT] (only while fishing and no board is up; on the cards the note is a line inside the card);
 * - at the left, one slot [HUD_SLOT] for the left panel (lake, bait and its picker, fight, goals) — or, while it is
 *   open, the menu [MENU_SLOT] in the same place (the left panel folds; 0.5.0 drew the menu half under it) — and the
 *   「菜单」 button above it [MENU_BTN_SLOT], always there.
 * - The 鱼类图鉴 and 钓场地图 are system windows: the system places them (no app API for it), so they are not in here.
 */
object PanelLayout {
    val EYE = Vec3(0.0, Layout.EYE, 0.0)
    /** The least gap, degrees, between two panels up together, and between a panel and what it must not hide. */
    const val MIN_GAP = 2.0

    const val CARD = "card"
    const val INTRO = "intro"
    const val RESULT = "result"
    const val HUD = "hud"
    const val MENU = "menu"
    const val MENU_BTN = "menubtn"
    const val NOTE = "note"
    const val TUTOR = "tutor"

    /**
     * A panel facing the eye: its centre [yaw]° right of straight ahead, [pitch]° up, [dist] m from the eye; [w] × [h]
     * its largest size (m). [top]: the panel is a fixed slot this size and its content hangs from the top edge (the
     * left panel: its top line and buttons stay put as its content grows and shrinks); else it is as big as its content,
     * centred.
     */
    class Slot(val id: String, val yaw: Double, val pitch: Double, val dist: Double, val w: Double, val h: Double, val top: Boolean = false) {
        val at: Vec3 get() = EYE + Layout.dirOf(pitch, yaw) * dist
        /** The SDK's EulerAngles(pitch, yaw, 0): pitched about its own x, then turned about y — facing the eye. */
        val eulerPitch: Float get() = pitch.toFloat()
        val eulerYaw: Float get() = (-yaw).toFloat()
        private val q get() = Math.toRadians(-yaw)
        private val p get() = Math.toRadians(pitch)
        val right: Vec3 get() = Vec3(cos(q), 0.0, -sin(q))
        val up: Vec3 get() = Vec3(sin(p) * sin(q), cos(p), sin(p) * cos(q))
        fun rect(): Rect = Rect(id, at, right, up, w, h)
        /** What is drawn, [dw] × [dh] (measured): hanging from the top edge of a [top] slot, else centred. */
        fun drawn(dw: Double, dh: Double): Rect = if (top) Rect(id, at + up * ((h - dh) / 2), right, up, dw, dh) else Rect(id, at, right, up, dw, dh)
    }

    /** A rectangle in the scene: centre, unit axes across and up, size. */
    class Rect(val id: String, val c: Vec3, val right: Vec3, val up: Vec3, val w: Double, val h: Double) {
        /** A point of it, [u] and [v] from -0.5 to 0.5 across and up. */
        fun point(u: Double, v: Double) = c + right * (u * w) + up * (v * h)

        /** Its outline, [n] points to a side. */
        fun outline(n: Int = 40): List<Vec3> = buildList {
            val corners = listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5)
            for (k in 0 until 4) {
                val (u0, v0) = corners[k]
                val (u1, v1) = corners[(k + 1) % 4]
                for (i in 0 until n) {
                    val t = i.toDouble() / n
                    add(point(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t))
                }
            }
        }

        /** Whether the sight line from [eye] through [p] passes through it (it hides [p], or [p] hides it). */
        fun covers(p: Vec3, eye: Vec3 = EYE): Boolean {
            val n = right.cross(up)
            val dir = p - eye
            val den = n.dot(dir)
            if (kotlin.math.abs(den) < 1e-12) return false
            val t = n.dot(c - eye) / den
            if (t <= 0) return false
            val hit = eye + dir * t - c
            return kotlin.math.abs(hit.dot(right)) <= w / 2 && kotlin.math.abs(hit.dot(up)) <= h / 2
        }

        /** Its extent seen from [eye]: yaw from..to (right +), pitch from..to (up +), degrees. */
        fun angles(eye: Vec3 = EYE): DoubleArray {
            var y0 = 999.0; var y1 = -999.0; var p0 = 999.0; var p1 = -999.0
            for (o in outline(10)) {
                val (y, p) = yawPitch(o, eye)
                y0 = minOf(y0, y); y1 = maxOf(y1, y); p0 = minOf(p0, p); p1 = maxOf(p1, p)
            }
            return doubleArrayOf(y0, y1, p0, p1)
        }
    }

    /** Degrees right of straight ahead and up from level, of [p] seen from [eye]. */
    fun yawPitch(p: Vec3, eye: Vec3 = EYE): Pair<Double, Double> {
        val d = (p - eye).normalized()
        return Math.toDegrees(atan2(d.x, -d.z)) to Math.toDegrees(asin(d.y.coerceIn(-1.0, 1.0)))
    }

    private fun angle(a: Vec3, b: Vec3, eye: Vec3): Double {
        val da = (a - eye).normalized(); val db = (b - eye).normalized()
        return Math.toDegrees(acos(da.dot(db).coerceIn(-1.0, 1.0)))
    }

    /** The smallest angle between [a] and [b] seen from [eye]; 0 when they touch, cross or one lies over the other. */
    fun gap(a: Rect, b: Rect, eye: Vec3 = EYE): Double {
        val oa = a.outline(); val ob = b.outline()
        if (oa.any { b.covers(it, eye) } || ob.any { a.covers(it, eye) } || a.covers(b.c, eye) || b.covers(a.c, eye)) return 0.0
        var least = 180.0
        for (p in oa) for (q in ob) least = minOf(least, angle(p, q, eye))
        return least
    }

    /** The smallest angle between [r] and the points [pts] seen from [eye]; 0 when it hides one of them. */
    fun gapTo(r: Rect, pts: List<Vec3>, eye: Vec3 = EYE): Double {
        if (pts.any { r.covers(it, eye) }) return 0.0
        val o = r.outline(16)
        var least = 180.0
        for (p in pts) for (q in o) least = minOf(least, angle(p, q, eye))
        return least
    }

    // ---- The places ----

    /** The panels in front stand this many degrees left of straight ahead: clear of the rod rising on the right. */
    const val FRONT_YAW = -12.0
    /** The left panel's slot, the menu's, and the menu button above them. */
    const val SIDE_YAW = -51.0

    val CARD_SLOT = Slot(CARD, FRONT_YAW, 2.6, 1.1, 0.50, 0.72)
    val INTRO_SLOT = Slot(INTRO, FRONT_YAW, 2.6, 1.1, 0.48, 0.46)
    val RESULT_SLOT = Slot(RESULT, FRONT_YAW, 2.6, 1.1, 0.48, 0.38)
    /** The note line, 8° over the eyes (over the water and the float). */
    val NOTE_SLOT = Slot(NOTE, FRONT_YAW, 8.0, 1.15, 0.50, 0.13)
    /** The tutorial board 6° under the eyes 1 m out; 10° over them while the float or fish is out there (it would hide it). */
    val TUTOR_LOW = Slot(TUTOR, FRONT_YAW, -6.0, 1.0, 0.50, 0.32)
    val TUTOR_HIGH = Slot(TUTOR, FRONT_YAW, 10.0, 1.0, 0.50, 0.32)
    /** 0.8 m out, 51° left and 20° down (0.5.0: 45° left, 26° down), its right edge clear of a cast 35° to the left. */
    val HUD_SLOT = Slot(HUD, SIDE_YAW, -20.0, 0.8, 0.33, 0.38, top = true)
    val MENU_SLOT = Slot(MENU, SIDE_YAW, -20.0, 0.8, 0.33, 0.38, top = true)
    val MENU_BTN_SLOT = Slot(MENU_BTN, SIDE_YAW, -2.0, 0.8, 0.13, 0.045)

    val ALL = listOf(CARD_SLOT, INTRO_SLOT, RESULT_SLOT, NOTE_SLOT, TUTOR_LOW, TUTOR_HIGH, HUD_SLOT, MENU_SLOT, MENU_BTN_SLOT)

    /**
     * The panels up together. [phase]: the game's phase (CARD, INTRO, PLAYING, RESULT); [menu] the menu is open (it takes
     * the left slot, the left panel folds); [board] the tutorial board or the net's how-to is up ([high]: lifted over
     * the float); [note] a note is waiting (shown only while fishing with no board; the board carries it otherwise).
     */
    fun shown(phase: String, menu: Boolean, board: Boolean, high: Boolean, note: Boolean): List<Slot> = buildList {
        add(MENU_BTN_SLOT)
        when (phase) {
            "CARD" -> add(CARD_SLOT)
            "INTRO" -> add(INTRO_SLOT)
            "RESULT" -> add(RESULT_SLOT)
        }
        if (menu) add(MENU_SLOT) else if (phase == "INTRO" || phase == "PLAYING") add(HUD_SLOT)
        if (phase == "PLAYING") {
            if (board) add(if (high) TUTOR_HIGH else TUTOR_LOW) else if (note) add(NOTE_SLOT)
        }
    }

    // ---- What no panel may hide ----

    /** The rod resting in its holder (cards, ready to cast): from its butt to its tip. */
    fun rodAtRest(): List<Vec3> {
        val d = Layout.dirOf(Layout.READY_PITCH, 0.0)
        return (-2..47).map { k -> Layout.REST_GRIP + d * (k * 0.05) }
    }

    /** The grip while the rod points at the float or the fish (the line out), for casts up to 35° either side. */
    fun gripOut(): List<Vec3> = (-7..7).flatMap { k -> listOf(Layout.restGrip(Layout.WAIT_PITCH, k * 5.0), Layout.restGrip(Layout.FIGHT_PITCH, k * 5.0)) }

    /** The landing net on its rest (its two colliders' corners and the grip). */
    fun net(): List<Vec3> = Layout.netRestBoxes().flatMap { b ->
        listOf(-1.0, 1.0).flatMap { i -> listOf(-1.0, 1.0).flatMap { j -> listOf(-1.0, 1.0).map { k -> b.c + b.ax[0] * (i * b.half[0]) + b.ax[1] * (j * b.half[1]) + b.ax[2] * (k * b.half[2]) } } }
    } + Layout.NET_GRIP

    /** The bait box (its pinch box's corners and centre). */
    fun baitBox(): List<Vec3> = listOf(-1.0, 1.0).flatMap { i -> listOf(-1.0, 1.0).flatMap { j -> listOf(-1.0, 1.0).map { k -> Layout.BAIT + Vec3(i * 0.1, j * 0.06, k * 0.08) } } } + Layout.BAIT

    /** Where the float lands: 4–16 m out, up to 35° either side, on the water. */
    fun floatArea(): List<Vec3> = (-14..14).flatMap { a -> (8..32).map { d -> Layout.dirOf(0.0, a * 2.5).let { u -> Vec3(u.x * d * 0.5, 0.0, u.z * d * 0.5) } } }

    /** The tired fish at the bank, where it is netted. */
    fun fishAtBank(): List<Vec3> = (-5..5).flatMap { a -> listOf(0.6, 1.0, 1.4, 1.8).map { d -> Layout.fishForNet(a * 5.0, d) } }

    /** The ice hole (ice lakes). */
    val ICE_HOLE = Vec3(0.2, 0.0, -0.75)

    /** What must stay in sight now: [lineOut] the float or the fish is out (WAIT..NETTABLE); [nettable] the fish is at the bank. */
    fun targets(phase: String, lineOut: Boolean, nettable: Boolean): Map<String, List<Vec3>> = buildMap {
        val out = phase == "PLAYING" && lineOut
        if (out) put("grip", gripOut()) else put("rod", rodAtRest())
        put("net", net())
        put("bait box", baitBox())
        if (out && !nettable) put("float area", floatArea())
        if (phase == "PLAYING" && nettable) put("fish at the bank", fishAtBank())
        if (phase == "PLAYING") put("ice hole", listOf(ICE_HOLE))
    }

    // ---- The debug hook `panels` ----

    /** The panels [shown] as drawn ([sizes]: measured w, h in metres, by id), then their gaps and what they keep clear. */
    fun report(shown: List<Slot>, sizes: Map<String, Pair<Double, Double>>, targets: Map<String, List<Vec3>>): List<String> {
        val rects = shown.map { s -> sizes[s.id]?.let { (w, h) -> s.drawn(w, h) } ?: s.rect() }
        val out = ArrayList<String>()
        for ((s, r) in shown.zip(rects)) {
            val a = r.angles()
            val m = sizes[s.id]
            val over = if (m != null && (m.second > s.h + 0.005 || m.first > s.w + 0.005)) " OVERFLOW (budget %.2fx%.2f)".format(s.w, s.h) else ""
            out += "panel %s: yaw %.1f..%.1f pitch %.1f..%.1f, %s%s".format(s.id, a[0], a[1], a[2], a[3],
                if (m != null) "drawn %.2fx%.2f m (slot %.2fx%.2f)".format(m.first, m.second, s.w, s.h) else "slot %.2fx%.2f (not measured)".format(s.w, s.h), over)
        }
        val bad = ArrayList<String>()
        var closest = 180.0; var closestPair = "-"
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val g = gap(rects[i], rects[j])
            if (g < closest) { closest = g; closestPair = "${rects[i].id}-${rects[j].id}" }
            if (g < MIN_GAP) bad += "${rects[i].id}-${rects[j].id} %.1f deg".format(g)
        }
        out += if (bad.isEmpty()) "panels overlaps: none (closest $closestPair %.1f deg)".format(if (rects.size > 1) closest else 0.0) else "panels OVERLAP: ${bad.joinToString(", ")}"
        val hid = ArrayList<String>()
        val clear = ArrayList<String>()
        for ((name, pts) in targets) {
            val g = rects.minOfOrNull { gapTo(it, pts) } ?: 180.0
            val who = rects.minByOrNull { gapTo(it, pts) }?.id ?: "-"
            if (g < MIN_GAP) hid += "$name by $who (%.1f deg)".format(g) else clear += "$name %.1f".format(g)
        }
        out += if (hid.isEmpty()) "panels hide nothing: ${clear.joinToString(", ")} deg" else "panels HIDE: ${hid.joinToString(", ")}"
        return out
    }
}

/**
 * Debug builds' probe of what the panels really drew (written by the panels on the main thread as they lay out and
 * compose; read by the `panels` and `ui` hooks).
 */
object PanelProbe {
    /** Measured sizes (w, h in metres) by panel id. */
    val sizes = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()
    /** The panels the stage shows now. */
    @Volatile var shown: List<PanelLayout.Slot> = emptyList()
    /** The left panel's texts as last composed, when (uptime ms) and how many times. */
    @Volatile var hud = ""
    @Volatile var hudAt = 0L
    @Volatile var hudCount = 0
}
