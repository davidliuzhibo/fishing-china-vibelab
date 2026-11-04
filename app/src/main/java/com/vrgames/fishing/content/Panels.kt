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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.PhysicalLengthConverter
import com.vrgames.fishing.LOG_ID
import com.vrgames.fishing.MAP_ID
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.game.Game

const val PANEL_CARD = "card"
const val PANEL_INTRO = "intro"
const val PANEL_HUD = "hud"
const val PANEL_RESULT = "result"
const val PANEL_MENU = "menu"
const val PANEL_NOTE = "note"

val Ink = Color(0xFFF1F4F2)
val Lake = Color(0xFF7FD0D8)
val Sun = Color(0xFFF2C46A)
val Dim = Color(0xFFB8C2C0)
val Danger = Color(0xFFFF6B5A)
private val PanelBg = Color(0xE6142024)
val WindowBg = Color(0xF2142024)

@Composable
fun PhysicalLengthConverter.m(meters: Float): Dp = lengthToDp(meters, LengthUnit.Meters)

@Composable
fun PhysicalLengthConverter.sz(meters: Float) = m(meters).value.sp

@Composable
private fun PanelBox(c: PhysicalLengthConverter, width: Float, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.014f))
    Column(Modifier.width(c.m(width)).clip(shape).background(PanelBg).border(c.m(0.0013f), Color(0x667FD0D8), shape).padding(c.m(0.014f)), verticalArrangement = Arrangement.spacedBy(c.m(0.007f))) { content() }
}

@Composable
fun CallToAction(c: PhysicalLengthConverter, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.012f))
    Text(text, Modifier.clip(shape).background(Sun).border(c.m(0.002f), Color(0xFFFFF4D6), shape).clickable { onClick() }.padding(horizontal = c.m(0.018f), vertical = c.m(0.01f)), fontSize = c.sz(0.02f), color = Color(0xFF1B1408))
}

@Composable
fun Pill(c: PhysicalLengthConverter, text: String, color: Color = Dim, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.01f))
    Text(text, Modifier.clip(shape).border(c.m(0.0015f), color, shape).clickable { onClick() }.padding(horizontal = c.m(0.011f), vertical = c.m(0.007f)), fontSize = c.sz(0.015f), color = color)
}

private fun stars(n: Int) = "★".repeat(n) + "☆".repeat(3 - n)

@Composable
fun CardPanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val pr = Game.progress
    PanelBox(c, 0.5f) {
        Text("钓遍中国", fontSize = c.sz(0.034f), color = Sun)
        Text("西湖的柳岸、千岛湖的小船、漓江的竹筏、查干湖的冰面：坐下，抛竿，等一等，把各地的名鱼钓上来。", fontSize = c.sz(0.016f), color = Ink)
        Text("怎么玩", fontSize = c.sz(0.018f), color = Lake)
        Text("· 看着想抛的方向，捏一下开始蓄力，再捏一下抛出。\n· 浮漂轻轻点一下是鱼在试探，别动；整个沉下去才捏。\n· 中鱼后捏住右手边的绕线轮画圈收线；鱼冲刺时（线绷紧变红）停手，不然会断。\n· 鱼游近、没力气了，捏右边的抄网。左边的饵盒可以换饵。", fontSize = c.sz(0.015f), color = Ink)
        if (pr.stars > 0) Text("已得 ${pr.stars} / ${pr.maxStars} 颗星。", fontSize = c.sz(0.014f), color = Lake)
        CallToAction(c, if (pr.stars > 0) "继续 →" else "出发 →") { Game.start() }
    }
}

@Composable
fun IntroPanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val l = Game.lake
    PanelBox(c, 0.48f) {
        Text(l.name, fontSize = c.sz(0.03f), color = Sun)
        Text(l.place, fontSize = c.sz(0.014f), color = Dim)
        Text(l.story, fontSize = c.sz(0.016f), color = Ink)
        Text("这里的鱼：" + l.fish.joinToString("、") { Fish.byId(it.first).name }, fontSize = c.sz(0.014f), color = Lake)
        l.missions.forEachIndexed { k, m -> Text("${if (k == 0) "主线" else "★"}　${m.text}", fontSize = c.sz(0.014f), color = Ink) }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f))) {
            CallToAction(c, "开始钓鱼 →") { Game.begin() }
            Pill(c, "返回") { Game.toCard() }
        }
    }
}

@Composable
fun HudPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val t = Game.trip ?: return
    PanelBox(c, 0.26f) {
        Text(t.lake.name, fontSize = c.sz(0.02f), color = Sun)
        Text("饵：${t.bait.label}　抛竿 ${t.casts}　断线 ${t.breaks}", fontSize = c.sz(0.014f), color = Ink)
        when (t.state) {
            Trip.State.POWER -> Bar(c, "力度", t.power, Sun)
            Trip.State.FIGHT, Trip.State.NETTABLE -> {
                Bar(c, if (t.running > 0) "鱼在冲！停手！" else "张力", t.tension, if (t.tension > 0.75 || t.running > 0) Danger else Lake)
                Text("距离 ${"%.1f".format(t.distance)} 米", fontSize = c.sz(0.013f), color = Dim)
            }
            else -> Unit
        }
        for (m in t.lake.missions) Text((if (m.check(t)) "✔ " else "○ ") + m.text, fontSize = c.sz(0.013f), color = if (m.check(t)) Lake else Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
            if (t.done) CallToAction(c, "收竿") { Game.wrapUp() }
            Pill(c, "收线", Lake) { Game.reel(0.25) }
            Pill(c, "鱼类图鉴", Dim) { toggle(LOG_ID) }
        }
    }
}

@Composable
private fun Bar(c: PhysicalLengthConverter, label: String, value: Double, color: Color) {
    val f = value.coerceIn(0.0, 1.0).toFloat()
    Text(label, fontSize = c.sz(0.013f), color = color)
    Box(Modifier.width(c.m(0.23f)).height(c.m(0.01f)).clip(RoundedCornerShape(c.m(0.005f))).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxHeight().width(c.m(0.23f * f)).background(color))
    }
}

@Composable
fun ResultPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val t = Game.trip ?: return
    val pr = Game.progress
    PanelBox(c, 0.48f) {
        Text("${t.lake.name}　${stars(t.stars())}", fontSize = c.sz(0.028f), color = Sun)
        Text("钓到 ${t.catches.size} 条：" + t.catches.groupBy { it.species }.entries.joinToString("，") { (k, v) -> "${Fish.byId(k).name} ${v.size} 条" }, fontSize = c.sz(0.015f), color = Ink)
        Text("最大的一条 ${t.catches.maxByOrNull { it.jin }?.let { "${Fish.byId(it.species).name} ${it.jin} 斤" } ?: "—"}　抛竿 ${t.casts} 次　断线 ${t.breaks} 次", fontSize = c.sz(0.014f), color = Dim)
        val tr = Game.trophies
        if (tr > Game.trophiesBefore) Text(when (tr) { 1 -> "得到铜奖杯「铜鱼钩」！四处钓场都完成了。"; 2 -> "得到银奖杯！解锁「西湖月夜」夜钓，能钓到发光的锦鲤。"; else -> "得到金奖杯！金色鳙鱼标本挂上船头，隐藏鱼王「查干湖头鱼」出现了。" }, fontSize = c.sz(0.016f), color = Lake)
        Text("总星数 ${pr.stars} / ${pr.maxStars}", fontSize = c.sz(0.014f), color = Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f))) {
            if (Lakes.MAIN.any { (pr.best[it.id] ?: 0) == 0 }) CallToAction(c, "下一处 →") { Game.next() }
            Pill(c, "再钓一次") { Game.again() }
            Pill(c, "钓场地图", Lake) { toggle(MAP_ID) }
        }
    }
}

@Composable
fun NotePanel(c: PhysicalLengthConverter) {
    val n = Game.note ?: return
    val shape = RoundedCornerShape(c.m(0.012f))
    Text(n, Modifier.widthIn(max = c.m(0.6f)).clip(shape).background(PanelBg).padding(c.m(0.01f)), fontSize = c.sz(0.018f), color = Sun)
}

@Composable
fun MenuPanel(c: PhysicalLengthConverter, toggle: (String) -> Unit, faceMe: () -> Unit, quit: () -> Unit, version: String) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    if (!Game.menuOpen) {
        Pill(c, "菜单", Sun) { Game.menuOpen = true }
        return
    }
    PanelBox(c, 0.26f) {
        Pill(c, if (Game.window == MAP_ID) "收起钓场地图" else "钓场地图（选钓场、奖杯）", Sun) { toggle(MAP_ID) }
        Pill(c, if (Game.window == LOG_ID) "收起鱼类图鉴" else "鱼类图鉴") { toggle(LOG_ID) }
        Pill(c, "把钓位转到我正前方") { faceMe() }
        Pill(c, if (Game.musicOn) "环境声：开" else "环境声：关") { Game.toggleMusic() }
        if (Game.confirmRestart) {
            Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
                Pill(c, "确定清空进度", Danger) { Game.confirmRestart = false; Game.menuOpen = false; Game.restart() }
                Pill(c, "算了") { Game.confirmRestart = false }
            }
        } else Pill(c, "清空进度从头开始") { Game.confirmRestart = true }
        Pill(c, "退出") { quit() }
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
