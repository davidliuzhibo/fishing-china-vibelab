package com.vrgames.fishing.content

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.vrgames.fishing.LOG_ID
import com.vrgames.fishing.MAP_ID
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.game.Game

private val CardBg = Color(0x26F1F4F2)

/** 鱼类图鉴: every fish, its record and a true note. */
@Composable
fun LogWindow() {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    Column(Modifier.fillMaxSize().background(WindowBg).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WindowHeader(LOG_ID, "鱼类图鉴", 32.sp)
        for (f in Fish.ALL) {
            val rec = Game.progress.record[f.id]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.name + if (rec != null) "　最大 $rec 斤" else "　还没钓到", fontSize = 22.sp, color = if (rec != null) Sun else Dim)
                Text(f.note, fontSize = 17.sp, color = Ink)
                Text("爱吃：" + f.likes.joinToString("、") { it.label } + "　" + (if (f.rocks) "藏在石头边" else if (f.far) "在远处深水" else "在近岸浅水"), fontSize = 15.sp, color = Lake)
            }
        }
        Text("鱼的习性做了游戏化的简化。", fontSize = 15.sp, color = Dim)
    }
}

/** 钓场地图: every lake, its stars (pick one), and the trophies. */
@Composable
fun MapWindow() {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val pr = Game.progress
    val context = LocalContext.current
    val treasure: ImageBitmap? = remember { runCatching { context.assets.open("textures/treasure.jpg").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull() }
    Column(Modifier.fillMaxSize().background(WindowBg).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WindowHeader(MAP_ID, "钓场地图", 32.sp)
        Text("总星数 ${pr.stars} / ${pr.maxStars}", fontSize = 18.sp, color = Dim)
        for (l in Lakes.ALL) {
            val open = pr.open(l)
            if (l.hidden && !open) continue
            val s = pr.best[l.id] ?: 0
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${l.name}　${l.place}", fontSize = 20.sp, color = if (open) Sun else Dim)
                    Text(if (s > 0) "★".repeat(s) + "☆".repeat(3 - s) else if (open) "还没去过" else "还没开放", fontSize = 16.sp, color = Ink)
                }
                if (open) Button(onClick = { Game.open(l); Game.closeWindow(MAP_ID) }) { Text("去这里", fontSize = 20.sp) }
            }
        }
        Text("奖杯", fontSize = 24.sp, color = Lake)
        for ((name, rule, got, reward) in listOf(
            Quad("铜奖杯 · 铜鱼钩", "四处钓场都完成。", pr.bronze, "一枚铜鱼钩。"),
            Quad("银奖杯 · 西湖月夜", "拿到一半以上的星。", pr.silver, "解锁夜钓：月光下钓发光的锦鲤。"),
            Quad("金奖杯 · 金色鳙鱼", "拿满全部星。", pr.gold, "金色鳙鱼标本、隐藏鱼王「查干湖头鱼」和珍藏画。"),
        )) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (got) "$name　已得到" else "$name　？", fontSize = 20.sp, color = if (got) Sun else Dim)
                Text(rule, fontSize = 16.sp, color = Ink)
                Text(if (got) reward else "得到后揭晓。", fontSize = 16.sp, color = if (got) Lake else Dim)
            }
        }
        if (pr.gold && treasure != null) Image(treasure, "珍藏画", Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
    }
}

private data class Quad(val a: String, val b: String, val c: Boolean, val d: String)
