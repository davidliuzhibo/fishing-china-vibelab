package com.vrgames.fishing.content

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.event.SceneEvents
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureCreateOption
import com.pico.spatial.core.ecs.resource.TextureEncoding
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.content.toColor4
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.vrgames.fishing.LOG_ID
import com.vrgames.fishing.MAP_ID
import com.vrgames.fishing.core.Lakes
import com.vrgames.fishing.game.AutoPilot
import com.vrgames.fishing.game.Game
import com.vrgames.fishing.render.LakeView
import com.vrgames.fishing.render.Music
import com.vrgames.fishing.render.Sfx
import com.vrgames.fishing.render.Sound
import com.vrgames.fishing.render.setPos
import com.vrgames.fishing.render.shared
import com.vrgames.fishing.render.transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The fishing place around the seated player (a full-immersion Stage). Any pinch over the water (a
 * wide invisible wall ahead takes it) is the one button: swing, cast, strike; it counts at the
 * moment the fingers close. Pinch and circle the reel to wind in; pinch the bait box and the net.
 */
@Composable
fun LakeStage() {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val navigator = LocalSpatialNavigator.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val head = remember { HMDTrackingProvider() }
    val subscription = remember { arrayOfNulls<Cancellable>(1) }
    var view by remember { mutableStateOf<LakeView?>(null) }
    val seat = remember { Seat() }
    val panels = remember { HashMap<String, Entity>() }
    val crank = remember { Crank() }
    val ibls = remember { HashMap<String, TextureResource>() }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val toggleWindow: (String) -> Unit = { id ->
        val shown = Game.window
        if (shown != null) Game.closeWindow(shown)
        if (shown != id) {
            Game.windowShown(id)
            navigator.openWindowContainer(id)
        }
    }

    DisposableEffect(Unit) {
        Game.init(context)
        Game.closeWindow = { id ->
            navigator.closeWindowContainer(id)
            Game.windowGone(id)
        }
        val hd = runCatching { head.start() }
        Log.i(TAG, "head tracking ${hd.getOrNull() ?: hd.exceptionOrNull()?.message}")
        onDispose {
            runCatching { head.stop() }
            subscription[0]?.cancel()
            Game.closeWindow(LOG_ID)
            Game.closeWindow(MAP_ID)
            Game.closeWindow = {}
        }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val music = Music(context).also {
            it.muted = !Game.musicOn
            Game.music = it
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) music.resume() else if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) music.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            music.release()
            Game.music = null
        }
    }
    LaunchedEffect(Game.lake, view) {
        val v = view ?: return@LaunchedEffect
        v.build(Game.lake)
        ibls[Game.lake.sky]?.let { v.useIbl(it) }
    }
    LaunchedEffect(Game.faceRequest) { if (Game.faceRequest > 0) seat.again() }
    LaunchedEffect(Game.noteVersion) {
        val n = Game.note ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.length > 40) 9000 else 6000)
        if (Game.note == n) Game.note = null
    }
    LaunchedEffect(Game.phase, Game.menuOpen, Game.note, view) {
        fun show(id: String, on: Boolean) = panels[id]?.let { if (it.enabled != on) it.enabled = on }
        val ph = Game.phase
        show(PANEL_CARD, ph == Game.Phase.CARD)
        show(PANEL_INTRO, ph == Game.Phase.INTRO)
        show(PANEL_RESULT, ph == Game.Phase.RESULT)
        show(PANEL_HUD, ph == Game.Phase.PLAYING)
        show(PANEL_NOTE, Game.note != null)
        show(PANEL_MENU, true)
    }
    LaunchedEffect(Game.debugOpen) {
        when (Game.debugOpen ?: return@LaunchedEffect) {
            "log" -> if (Game.window != LOG_ID) toggleWindow(LOG_ID)
            "map" -> if (Game.window != MAP_ID) toggleWindow(MAP_ID)
            "menu" -> Game.menuOpen = true
            "close" -> Game.window?.let { Game.closeWindow(it) }
        }
        Game.debugOpen = null
    }

    SpatialView(
        Modifier
            // Every finger-down: on the reel it starts a crank; on the box or the net it is theirs;
            // anywhere over the water it is the one button.
            .pointerInput(Unit) {
                detectSpatialPointerEvent(context, TargetEntity.any()) { infos ->
                    val v = view
                    if (v != null) for (i in infos) {
                        if (!i.isDownEvent()) continue
                        when (val id = v.hitOf(i.targetedEntity)) {
                            "reel" -> Unit
                            "bait" -> main.post { Game.cycleBait() }
                            "net" -> main.post { Game.net() }
                            "water" -> main.post { Game.press() }
                            else -> Log.i(INPUT_TAG, "down on $id")
                        }
                    }
                    false
                }
            }
            // Circling the reel winds it (a quarter turn per quarter circle of the hand).
            .pointerInput(1) {
                fun meters(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }.toDouble()
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, value -> crank.on = view?.hitOf(value.targetEntity) == "reel"; crank.reset() },
                    onDragEnd = { crank.on = false },
                    onDragCancel = { crank.on = false },
                ) { value ->
                    if (!crank.on) return@detectSpatialDragGesture
                    val turns = crank.move(meters(value.dragAmount.x), -meters(value.dragAmount.y))
                    if (turns != 0.0) main.post {
                        Game.reel(turns)
                        Game.sound?.play(Sfx.REEL)
                    }
                }
            },
        attachments = {
            AttachmentPanel(PANEL_CARD) { CardPanel(converter) }
            AttachmentPanel(PANEL_INTRO) { IntroPanel(converter) }
            AttachmentPanel(PANEL_HUD) { HudPanel(converter, toggleWindow) }
            AttachmentPanel(PANEL_RESULT) { ResultPanel(converter, toggleWindow) }
            AttachmentPanel(PANEL_NOTE) { NotePanel(converter) }
            AttachmentPanel(PANEL_MENU) {
                MenuPanel(converter, toggleWindow, faceMe = { Game.faceRequest++ }, quit = { context.findActivity()?.finishAndRemoveTask() }, version = appVersion)
            }
        },
        update = { _, attachments -> view?.let { attach(it, attachments, panels) } },
        initial = { content, attachments ->
            content.addEntity(Entity().apply {
                components.set(DirectionalLightComponent(Color(0xFFFFF1DC).toColor4(), 1200f, true))
                components[TransformComponent::class.java]?.setEulerAngles(EulerAngles(-45f, 30f, 0f))
            })
            val tex = HashMap<String, TextureResource>()
            withContext(Dispatchers.IO) {
                for (k in Lakes.ALL.map { it.sky }.distinct()) {
                    runCatching { TextureResource.load("textures/sky_$k.jpg", LoadType.FROM_ASSETS, TextureCreateOption()).shared() }.onSuccess { tex["sky_$k"] = it }
                    runCatching { TextureResource.load("textures/ibl_$k.ktx", LoadType.FROM_ASSETS, TextureCreateOption().apply { textureEncoding = TextureEncoding.LINEAR }).shared() }.onSuccess { ibls[k] = it }
                }
                runCatching { TextureResource.load("textures/water.jpg", LoadType.FROM_ASSETS, TextureCreateOption()).shared() }.onSuccess { tex["water"] = it }
            }
            val models = HashMap<String, Entity>()
            for (name in MODELS) {
                runCatching { Entity.loadSuspend("asset://models/$name.glb") }
                    .onSuccess { models[name] = it }
                    .onFailure { Log.i(TAG, "model $name: ${it.message}") }
            }
            val v = LakeView(models, tex)
            // A wide invisible wall ahead: a pinch anywhere over the water lands on it.
            val wall = Entity().apply {
                setPos(Vector3(0f, 1.5f, -6f))
                components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(30f, 12f, 0.2f))), PhysicsMaterialResource()))
                components.set(InteractableComponent())
            }
            v.root.addChild(wall)
            v.registerWater(wall)
            seat.root = v.root
            content.addEntity(v.root)
            attach(v, attachments, panels)
            Game.view = v
            Game.sound = Sound(v.root).also { it.load() }
            view = v
            AutoPilot.ready()
            subscription[0] = content.subscribe(SceneEvents.Update::class.java) { e ->
                val dt = e.deltaTime.toDouble().coerceIn(0.0, 0.1)
                val pose = runCatching { if (head.state == DataProvider.State.STARTED) head.latestData?.hmdPose else null }.getOrNull()
                seat.frame(pose?.position, pose?.rotation)
                main.post { Game.tick(dt) }
                synchronized(Game.lock) { Game.trip?.let { v.sync(it, Game.aimYaw) } }
                v.frame(dt)
            }
        },
    )
}

/** Turning the reel by moving the pinched hand in a circle: the angle swept, in turns. */
private class Crank {
    var on = false
    private var u = 0.0
    private var w = 0.0
    private var started = false

    fun reset() { started = false; u = 0.0; w = 0.0 }

    fun move(dx: Double, dy: Double): Double {
        val before = atan2(w, u)
        u += dx
        w += dy
        if (!started) { started = hypot(u, w) > 0.02; return 0.0 }
        // Kept near a circle of 5 cm, so the angle follows the hand.
        val r = hypot(u, w).coerceAtLeast(1e-6)
        u = u / r * 0.05
        w = w / r * 0.05
        var d = atan2(w, u) - before
        if (d > Math.PI) d -= 2 * Math.PI
        if (d < -Math.PI) d += 2 * Math.PI
        return kotlin.math.abs(d) / (2 * Math.PI)
    }
}

/** Textures and models loaded if present (see docs/素材来源.md). */
private val MODELS = listOf("sky", "rod", "baitbox", "boat", "raft", "crucian", "bream", "bighead", "silver", "culter", "mandarin", "catfish", "carp", "koi")

private fun attach(v: LakeView, a: SpatialViewAttachments, panels: HashMap<String, Entity>) {
    fun put(id: String, pos: Vector3, pitch: Float, yaw: Float = 0f) {
        val e = a.entity(id) ?: return
        if (panels[id] === e) return
        panels[id] = e
        v.root.addChild(e)
        e.setPos(pos)
        e.transform().eulerAngles = EulerAngles(pitch, yaw, 0f)
    }
    put(PANEL_CARD, Vector3(0f, 1.25f, -1.1f), 0f)
    put(PANEL_INTRO, Vector3(0f, 1.25f, -1.1f), 0f)
    put(PANEL_RESULT, Vector3(0f, 1.25f, -1.1f), 0f)
    put(PANEL_HUD, Vector3(-0.5f, 0.85f, -0.5f), -25f, 35f)
    put(PANEL_NOTE, Vector3(0f, 1.62f, -1.3f), 5f)
    put(PANEL_MENU, Vector3(-0.42f, 0.66f, -0.38f), -45f, 30f)
}

/** Where the player sits; turned once to the head's mean heading; tracks the aim (head yaw). */
private class Seat {
    var root: Entity? = null
    private var placed = false
    private var facing = 0f
    private val headings = ArrayList<Float>()
    private var started = 0L

    fun again() {
        placed = false
        headings.clear()
    }

    fun frame(eye: Vector3?, rot: Quat?) {
        Game.debugLook?.let { Game.aimYaw = it }
        if (rot == null || eye == null) return
        val f = rot.rotateVector(Vector3(0f, 0f, -1f))
        if (hypot(f.x, f.z) < 0.2f) return
        val heading = Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat()
        if (placed) {
            if (Game.debugLook == null) {
                var d = heading - facing
                while (d > 180) d -= 360
                while (d < -180) d += 360
                Game.aimYaw = -d.toDouble()
            }
            return
        }
        if (headings.isEmpty()) started = android.os.SystemClock.uptimeMillis()
        headings += heading
        if (android.os.SystemClock.uptimeMillis() - started < 1000) return
        val s = headings.sumOf { sin(Math.toRadians(it.toDouble())) }
        val c = headings.sumOf { cos(Math.toRadians(it.toDouble())) }
        facing = Math.toDegrees(atan2(s, c)).toFloat()
        placed = true
        root?.let {
            it.setPos(Vector3(eye.x, 0f, eye.z))
            it.transform().eulerAngles = EulerAngles(0f, facing, 0f)
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private const val TAG = "FishStage"
private const val INPUT_TAG = "FishInput"
