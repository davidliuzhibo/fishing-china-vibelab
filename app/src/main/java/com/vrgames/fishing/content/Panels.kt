package com.vrgames.fishing.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.PhysicalLengthConverter
import com.vrgames.fishing.LOG_ID
import com.vrgames.fishing.MAP_ID
import com.vrgames.fishing.core.Bait
import com.vrgames.fishing.core.BaitRules
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.LiveHud
import com.vrgames.fishing.core.PanelLayout
import com.vrgames.fishing.core.PanelProbe
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.core.Tutorial
import com.vrgames.fishing.game.AutoPilot
import com.vrgames.fishing.game.Game

const val PANEL_CARD = PanelLayout.CARD
const val PANEL_INTRO = PanelLayout.INTRO
const val PANEL_HUD = PanelLayout.HUD
const val PANEL_RESULT = PanelLayout.RESULT
const val PANEL_MENU = PanelLayout.MENU
const val PANEL_MENU_BTN = PanelLayout.MENU_BTN
const val PANEL_NOTE = PanelLayout.NOTE
const val PANEL_TUTOR = PanelLayout.TUTOR

val Ink = Color(0xFFF1F4F2)
val Lake = Color(0xFF7FD0D8)
val Sun = Color(0xFFF2C46A)
val Dim = Color(0xFFB8C2C0)
val Danger = Color(0xFFFF6B5A)
val Hint = Color(0xFFFFB067)
private val Grey = Color(0x80B8C2C0)
private val PanelBg = Color(0xE6142024)
val WindowBg = Color(0xF2142024)

@Composable
fun PhysicalLengthConverter.m(meters: Float): Dp = lengthToDp(meters, LengthUnit.Meters)

@Composable
fun PhysicalLengthConverter.sz(meters: Float) = m(meters).value.sp

/** Debug builds: each panel's drawn size in metres, for the `panels` hook (core.PanelProbe). */
@Composable
private fun Modifier.probe(c: PhysicalLengthConverter, id: String): Modifier {
    val density = LocalDensity.current
    return onSizeChanged { s ->
        if (!AutoPilot.debug) return@onSizeChanged
        fun m(px: Int) = with(density) { c.dpToLength(px.toDp(), LengthUnit.Meters).toDouble() }
        PanelProbe.sizes[id] = m(s.width) to m(s.height)
    }
}

/**
 * A panel's lacquered board, [slot]'s width (core.PanelLayout: where it stands and its largest size). A [PanelLayout.Slot.top]
 * slot is a fixed box of the slot's size with the board hanging from its top edge, so the board's top line and buttons
 * stay put as it grows and shrinks (the left panel, the menu in its place).
 *
 * v0.5.1: [content] is a lambda Compose skips when it is passed again unchanged — every one reads `Game.live` first
 * (a state renewed whenever something shown changes), or its texts would stay as they were (0.5.0's fight numbers).
 */
@Composable
private fun PanelBox(c: PhysicalLengthConverter, slot: PanelLayout.Slot, content: @Composable () -> Unit) {
    // v0.3 (PICO 1117: 「UI也比较粗糙」): a lacquered board — deep lake-green shading down to ink, a fine gold rim with a
    // thin inner line, like a fishing-lodge signboard; wider margins, the same light text on dark.
    val shape = RoundedCornerShape(c.m(0.016f))
    val inner = RoundedCornerShape(c.m(0.012f))
    val board = @Composable { mod: Modifier ->
        Box(mod.width(c.m(slot.w.toFloat())).probe(c, slot.id).clip(shape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xF21B3238), Color(0xF20D181B)))).border(c.m(0.0018f), Color(0xCCD9B266), shape).padding(c.m(0.004f))) {
            Column(Modifier.fillMaxWidth().border(c.m(0.0008f), Color(0x557FD0D8), inner).padding(horizontal = c.m(0.016f), vertical = c.m(0.012f)), verticalArrangement = Arrangement.spacedBy(c.m(0.007f))) { content() }
        }
    }
    if (slot.top) Box(Modifier.width(c.m(slot.w.toFloat())).height(c.m(slot.h.toFloat())), contentAlignment = Alignment.TopCenter) {
        board(Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
    } else board(Modifier)
}

@Composable
fun CallToAction(c: PhysicalLengthConverter, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.012f))
    Text(text, Modifier.clip(shape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF7D584), Color(0xFFE0A845)))).border(c.m(0.002f), Color(0xFFFFF4D6), shape).clickable { onClick() }.padding(horizontal = c.m(0.02f), vertical = c.m(0.011f)), fontSize = c.sz(0.02f), color = Color(0xFF1B1408))
}

@Composable
fun Pill(c: PhysicalLengthConverter, text: String, color: Color = Dim, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.01f))
    Text(text, Modifier.clip(shape).border(c.m(0.0015f), color, shape).clickable { onClick() }.padding(horizontal = c.m(0.011f), vertical = c.m(0.007f)), fontSize = c.sz(0.015f), color = color)
}

/** v0.5.1: a button that cannot be used now: grey, dashed-dark inside; a pinch on it says why ([onClick]). */
@Composable
fun GreyPill(c: PhysicalLengthConverter, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.01f))
    Text(text, Modifier.clip(shape).background(Color(0x40000000)).border(c.m(0.0015f), Grey, shape).clickable { onClick() }.padding(horizontal = c.m(0.011f), vertical = c.m(0.007f)), fontSize = c.sz(0.015f), color = Grey)
}

private fun stars(n: Int) = "★".repeat(n) + "☆".repeat(3 - n)

/** On the cards (lake, results) a note is a line inside the card (v0.5.1: the note line in front stood over them). */
@Composable
private fun CardNote(c: PhysicalLengthConverter) {
    val live = Game.live
    val n = Game.note ?: return
    Text(n, fontSize = c.sz(0.016f), color = Hint)
}

@Composable
fun CardPanel(c: PhysicalLengthConverter) {
    val live = Game.live
    PanelBox(c, PanelLayout.CARD_SLOT) {
        val l = Game.live
        val pr = Game.progress
        Text("钓遍中国", fontSize = c.sz(0.034f), color = Sun)
        Text("西湖的柳岸、千岛湖的小船、漓江的竹筏、查干湖的冰面：坐下，抛竿，等一等，把各地的名鱼钓上来。", fontSize = c.sz(0.016f), color = Ink)
        Text("怎么玩", fontSize = c.sz(0.018f), color = Lake)
        Text("· 选饵：左边面板上的「换饵」（或左下方的饵盒）。不同的鱼爱吃不同的饵，关卡目标里写着；线抛出去以后，收回来才能换。\n· 拿竿：右前方竿架上的竿柄在发金光，伸手握住它，或者看着它捏一下手指。拿起来以后不用一直捏着。\n· 抛竿：把拿竿的手抬过肩膀、往后带一点，听到「咔」，再像扔飞镖一样往前一甩。甩得越快抛得越远；朝左右甩就抛向左右。\n· 等鱼：手握着鱼竿，它就留在手里；张开手，鱼竿放回竿架。浮漂轻轻点一下是鱼在试探，别动；整个沉下去时，拿竿的手往上一抬（竿在竿架上时，手在竿柄旁往上抬），或者看着浮漂捏一下手指。\n· 收线：用空着的手在身前画圈（不用捏）；线变红、鱼往外冲时先停手，不然会断。\n· 抄网（可选）：鱼拉到岸边时，左前方的抄网会亮，拿起来从鱼下面兜；也可以一直收线把鱼拉上岸。\n· 第一次钓鱼有一步一步的教学（文字加语音），菜单里可以从头再学。", fontSize = c.sz(0.015f), color = Ink)
        if (pr.stars > 0) Text("已得 ${pr.stars} / ${pr.maxStars} 颗星。", fontSize = c.sz(0.014f), color = Lake)
        CardNote(c)
        CallToAction(c, if (pr.stars > 0) "继续 →" else "出发 →") { Game.start() }
    }
}

@Composable
fun IntroPanel(c: PhysicalLengthConverter) {
    val live = Game.live
    PanelBox(c, PanelLayout.INTRO_SLOT) {
        val v = Game.live
        val l = Game.lake
        Text(l.name, fontSize = c.sz(0.03f), color = Sun)
        Text(l.place, fontSize = c.sz(0.014f), color = Dim)
        Text(l.story, fontSize = c.sz(0.016f), color = Ink)
        Text("这里的鱼：" + l.fish.joinToString("、") { Fish.byId(it.first).name }, fontSize = c.sz(0.014f), color = Lake)
        l.missions.forEachIndexed { k, m -> Text("${if (k == 0) "主线" else "★"}　${m.text}", fontSize = c.sz(0.014f), color = Ink) }
        // v0.5.1: the bait, and where to change it (the left panel is up from this card on).
        Text("现在挂的饵：${v.bait?.label ?: "—"}。左边面板的「换饵」可以换。", fontSize = c.sz(0.014f), color = Lake)
        CardNote(c)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f))) {
            CallToAction(c, "开始钓鱼 →") { Game.begin() }
            Pill(c, "返回") { Game.toCard() }
        }
    }
}

/**
 * The left panel (v0.5.1): the lake; 「换饵：<饵>」 — gold while the tutorial's bait step asks for it, lake-blue whenever the
 * bait can be changed, grey with the reason under it from the cast until the fish is landed or lost; a hint when the
 * bait cannot catch this lake's goals; the bait list in place of the goals while it is open; the fight's tension,
 * strength, distance and turns, live; the goals; the buttons.
 */
@Composable
fun HudPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit) {
    val live = Game.live
    val t = Game.trip ?: return
    PanelBox(c, PanelLayout.HUD_SLOT) {
        val l = Game.live
        val bait = l.bait ?: t.bait
        val state = l.state ?: Trip.State.READY
        val baitLabel = if (l.picker) "收起换饵" else "换饵：${bait.label}"
        val why = if (l.baitOk) null else BaitRules.lockedWhy(state) ?: "现在不能换饵"
        // The hint while it can still help: the bait can be changed, or the float is out on a bait nothing here takes.
        val hint = if (l.baitOk || state == Trip.State.WAIT) BaitRules.hint(t) else null
        val baitStep = Game.tutorialStep == Tutorial.BAIT
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t.lake.name, fontSize = c.sz(0.02f), color = Sun)
            Spacer(Modifier.weight(1f))
            Text("已钓 ${l.catches} 条　抛竿 ${l.casts}　断线 ${l.breaks}", fontSize = c.sz(0.012f), color = Dim)
        }
        when {
            !l.baitOk -> GreyPill(c, baitLabel) { Game.baitButton("button") }
            baitStep -> CallToAction(c, baitLabel) { Game.baitButton("button") }
            else -> Pill(c, baitLabel, Lake) { Game.baitButton("button") }
        }
        if (why != null) Text(why, fontSize = c.sz(0.012f), color = Dim)
        if (hint != null) Text(hint, fontSize = c.sz(0.012f), color = Hint)
        var shown = ""
        if (l.picker) BaitPicker(c, t, l)
        else {
            if (l.fighting) shown = FightBlock(c, l)
            t.lake.missions.forEachIndexed { k, m ->
                val ok = (l.met and (1 shl k)) != 0
                Text((if (ok) "✔ " else "○ ") + m.text + (l.progress.getOrNull(k) ?: ""), fontSize = c.sz(0.013f), color = if (ok) Lake else Ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
                if ((l.met and 1) != 0) CallToAction(c, "收竿") { Game.wrapUp() }
                if (l.fighting) Pill(c, "收线", Lake) { Game.reelButton() }
                if (state == Trip.State.WAIT) Pill(c, "收竿重抛", Lake) { Game.reelIn() }
                Pill(c, "鱼类图鉴", Dim) { toggle(LOG_ID) }
            }
        }
        // Debug builds: what the hands look like to the game (fresh or frozen, fist, distance to the grip) — not while the
        // fight or the bait list needs the room (the `hud` hook logs it then).
        if (AutoPilot.debug && !l.fighting && !l.picker && Game.debugHands.isNotEmpty()) Text(Game.debugHands, fontSize = c.sz(0.0095f), color = Dim)
        // Debug builds: what was composed, for the `ui` hook (it shows whether the panel redraws).
        val text = "bait=「$baitLabel」 enabled=${l.baitOk}${if (baitStep) " (gold: tutorial step)" else ""} why=${why ?: "-"} hint=${hint ?: "-"} picker=${l.picker} state=$state$shown goals=${t.lake.missions.mapIndexed { k, m -> (if ((l.met and (1 shl k)) != 0) "✔" else "○") + m.text + (l.progress.getOrNull(k) ?: "") }.joinToString(" / ")} 已钓=${l.catches}"
        SideEffect {
            if (AutoPilot.debug) { PanelProbe.hud = text; PanelProbe.hudAt = android.os.SystemClock.uptimeMillis(); PanelProbe.hudCount++ }
        }
    }
}

/** The bait list: each bait, who takes it here (goal fish marked), the one on the hook ticked. */
@Composable
private fun BaitPicker(c: PhysicalLengthConverter, t: Trip, l: LiveHud) {
    val live = Game.live
    Text("选一种饵（目标里写着哪种鱼要用什么饵）：", fontSize = c.sz(0.012f), color = Dim)
    for (b in Bait.entries) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
        val on = b == l.bait
        Pill(c, (if (on) "✔ " else "") + b.label, if (on) Sun else Lake) { Game.pickBait(b) }
        val fans = BaitRules.fansHere(t.lake, b)
        Text(BaitRules.line(t, b), fontSize = c.sz(0.012f), color = if (fans.isEmpty()) Dim else Ink)
    }
}

/** The fight's numbers (tension, strength, distance, turns), from the live snapshot; returns them for the probe. */
@Composable
private fun FightBlock(c: PhysicalLengthConverter, l: LiveHud): String {
    val live = Game.live
    val tensionColor = if (l.tension > 75 || l.running) Danger else Lake
    val strengthColor = if (l.strength < (Trip.NET_TIRED * 100).toInt()) Lake else Sun
    val tensionLabel = if (l.running) "鱼在冲！停手！" else "张力 ${l.tension}%"
    val strengthLabel = "鱼的力气 ${l.strength}%"
    val distLabel = "距离 ${"%.1f".format(l.distance)} 米　已收 ${l.turns} 圈"
    val nettable = l.state == Trip.State.NETTABLE
    Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.01f))) {
        Column(Modifier.width(c.m(0.135f))) { Bar(c, tensionLabel, l.tension / 100.0, tensionColor, 0.135f) }
        Column(Modifier.width(c.m(0.135f))) { Bar(c, strengthLabel, l.strength / 100.0, strengthColor, 0.135f) }
    }
    Text(distLabel, fontSize = c.sz(0.012f), color = Ink)
    Text(
        if (nettable) "鱼到岸边：可拿抄网兜，或继续画圈拉上岸" else "画圈收线；${Trip.NET_DIST} 米内、力气低于 ${(Trip.NET_TIRED * 100).toInt()}% 可抄网",
        fontSize = c.sz(0.012f), color = if (nettable) Sun else Dim,
    )
    return " | $tensionLabel | $strengthLabel | $distLabel"
}

@Composable
private fun Bar(c: PhysicalLengthConverter, label: String, value: Double, color: Color, width: Float) {
    val f = value.coerceIn(0.0, 1.0).toFloat()
    Text(label, fontSize = c.sz(0.012f), color = color)
    Box(Modifier.width(c.m(width)).height(c.m(0.01f)).clip(RoundedCornerShape(c.m(0.005f))).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().width(c.m(width * f)).background(color))
    }
}

@Composable
fun ResultPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit) {
    val live = Game.live
    val t = Game.trip ?: return
    PanelBox(c, PanelLayout.RESULT_SLOT) {
        val l = Game.live
        val pr = Game.progress
        Text("${t.lake.name}　${stars(t.stars())}", fontSize = c.sz(0.028f), color = Sun)
        Text("钓到 ${t.catches.size} 条：" + t.catches.groupBy { it.species }.entries.joinToString("，") { (k, v) -> "${Fish.byId(k).name} ${v.size} 条" }, fontSize = c.sz(0.015f), color = Ink)
        Text("最大的一条 ${t.catches.maxByOrNull { it.jin }?.let { "${Fish.byId(it.species).name} ${it.jin} 斤" } ?: "—"}　抛竿 ${t.casts} 次　断线 ${t.breaks} 次", fontSize = c.sz(0.014f), color = Dim)
        val tr = Game.trophies
        if (tr > Game.trophiesBefore) Text(when (tr) { 1 -> "得到铜奖杯「铜鱼钩」！四处钓场都完成了。"; 2 -> "得到银奖杯！解锁「西湖月夜」夜钓，能钓到发光的锦鲤。"; else -> "得到金奖杯！金色鳙鱼标本挂上船头，隐藏鱼王「查干湖头鱼」出现了。" }, fontSize = c.sz(0.016f), color = Lake)
        Text("总星数 ${pr.stars} / ${pr.maxStars}", fontSize = c.sz(0.014f), color = Dim)
        CardNote(c)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f))) {
            if (Lakes.MAIN.any { (pr.best[it.id] ?: 0) == 0 }) CallToAction(c, "下一处 →") { Game.next() }
            Pill(c, "再钓一次") { Game.again() }
            Pill(c, "钓场地图", Lake) { toggle(MAP_ID) }
        }
    }
}

@Composable
fun NotePanel(c: PhysicalLengthConverter) {
    val live = Game.live
    val n = Game.note ?: return
    val shape = RoundedCornerShape(c.m(0.012f))
    Text(n, Modifier.widthIn(max = c.m(PanelLayout.NOTE_SLOT.w.toFloat())).probe(c, PANEL_NOTE).clip(shape).background(PanelBg).padding(c.m(0.01f)), fontSize = c.sz(0.018f), color = Sun)
}

/** v0.5.1: the 「菜单」 button, always in the same place above the left panel; 「收起菜单」 while the menu is open. */
@Composable
fun MenuButton(c: PhysicalLengthConverter) {
    val live = Game.live
    val shape = RoundedCornerShape(c.m(0.012f))
    val open = Game.menuOpen
    Text(if (open) "收起菜单" else "菜单", Modifier.probe(c, PANEL_MENU_BTN).clip(shape).background(PanelBg).border(c.m(0.0018f), Sun, shape).clickable { Game.menuOpen = !open }.padding(horizontal = c.m(0.014f), vertical = c.m(0.008f)), fontSize = c.sz(0.016f), color = Sun)
}

/** The menu, in the left panel's place while it is open (v0.5.1: 0.5.0 drew it half under the left panel). */
@Composable
fun MenuPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit, faceMe: () -> Unit, quit: () -> Unit, version: String) {
    val live = Game.live
    PanelBox(c, PanelLayout.MENU_SLOT) {
        val l = Game.live
        Text("菜单", fontSize = c.sz(0.02f), color = Sun)
        Pill(c, if (Game.window == MAP_ID) "收起钓场地图" else "钓场地图（选钓场、奖杯）", Sun) { toggle(MAP_ID) }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
            Pill(c, if (Game.window == LOG_ID) "收起鱼类图鉴" else "鱼类图鉴") { toggle(LOG_ID) }
            Pill(c, if (Game.musicOn) "环境声：开" else "环境声：关") { Game.toggleMusic() }
        }
        Pill(c, "新手教学（从头再学一遍）", Lake) { Game.tutorialFromMenu() }
        Pill(c, "把钓位转到我正前方") { faceMe() }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
            if (Game.confirmRestart) {
                Pill(c, "确定清空进度", Danger) { Game.confirmRestart = false; Game.menuOpen = false; Game.restart() }
                Pill(c, "算了") { Game.confirmRestart = false }
            } else {
                Pill(c, "清空进度从头开始") { Game.confirmRestart = true }
                Pill(c, "退出") { quit() }
            }
        }
        Text("进度自动保存。版本 $version", fontSize = c.sz(0.012f), color = Dim)
        Pill(c, "收起菜单") { Game.menuOpen = false }
    }
}

@Composable
fun WindowHeader(id: String, title: String, size: TextUnit) {
    val copy = remember { Any() }
    DisposableEffect(copy) {
        Game.windowShown(id, copy)
        onDispose { Game.windowGone(id, copy) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = size, color = Sun)
        Spacer(Modifier.weight(1f))
        Button(onClick = { Game.closeWindow(id) }) { Text("关闭", fontSize = 22.sp) }
    }
}


/**
 * v0.5 (device test 2026-11-20: 「希望从一开始就有一步一步的教学，文字加语音」): the tutorial's board in front of the
 * player — the step, what to do (exactly what the game accepts), a setback's hint, 「再听一遍」 and 「跳过教学」; it
 * moves on only when the game sees the step done. Outside the tutorial the same board says how to use the net while
 * the fish can be netted (with 「放下抄网」 once it is in a hand). Replaces 0.4's 「手把手」 board beside the rod.
 * v0.5.1: its first step is the bait (「知道了」 here, or 「换饵」 on the left panel).
 */
@Composable
fun TutorialPanel(c: PhysicalLengthConverter) {
    val live = Game.live
    val step = Game.tutorialStep
    if (step == 0 && !Game.netHint) return
    PanelBox(c, PanelLayout.TUTOR_LOW) {
        val l = Game.live
        val tut = Game.tutorial
        val now = Game.tutorialStep
        if (now > 0) {
            Text(tut.header, fontSize = c.sz(0.022f), color = Sun)
            Text(tut.current?.text.orEmpty(), fontSize = c.sz(0.019f), color = Ink)
        } else {
            Text(Tutorial.NET.title, fontSize = c.sz(0.022f), color = Sun)
            Text(Tutorial.NET.text, fontSize = c.sz(0.019f), color = Ink)
        }
        Game.boardNote?.let { Text(it, fontSize = c.sz(0.018f), color = Hint) }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f)), verticalAlignment = Alignment.CenterVertically) {
            if (now == Tutorial.BAIT) CallToAction(c, "知道了") { Game.tutorialAck() }
            if (now == Tutorial.TOTAL + 1) CallToAction(c, "开始钓鱼 →") { Game.endTutorial() }
            if (Game.netInHand) CallToAction(c, "放下抄网") { Game.dropNet() }
            Pill(c, "再听一遍", Lake) { Game.replayVoice() }
            if (now in 1..Tutorial.TOTAL) Pill(c, "跳过教学") { Game.skipTutorial() }
            if (now == 0) Pill(c, "知道了") { Game.hideNetHint() }
        }
    }
}
