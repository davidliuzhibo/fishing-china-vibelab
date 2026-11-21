package com.vrgames.fishing.content

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.pico.spatial.core.ecs.ViewCoordinateSpace
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
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandPose
import com.vrgames.fishing.core.HandReading
import com.vrgames.fishing.core.Layout
import com.vrgames.fishing.core.Vec3
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
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.render.LakeView
import com.pico.spatial.core.container.SpatialViewContent
import com.vrgames.fishing.render.Music
import com.vrgames.fishing.render.Sound
import com.vrgames.fishing.render.Voice
import com.vrgames.fishing.render.setPos
import com.vrgames.fishing.render.shared
import com.vrgames.fishing.render.toVector3
import com.vrgames.fishing.render.transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The fishing place around the seated player (a full-immersion Stage).
 *
 * v0.5 input layer (device test 2026-11-20 on 0.4.0): the game trusts only
 * - the system's gaze-and-pinch press events, with the pinching hand's position (to tell which hand pinched);
 * - the system's drags (a pinch held and moved: the rod with no hand tracking, the reel's crank);
 * - FRESH hand positions from tracking (new readings by the tracker's own timestamp), and a fist only to take things.
 * Never the pinch strength from tracking, never the hand's orientation. All of it goes to content.HandPlay
 * (core.Angler). The 0.4 ways with no live hands — a pinch on the water starting the old power swing and a second one
 * casting where the head pointed, the pinch-drag cast — are gone; without hands the rod is pinch-held and the drag
 * casts it with the same two steps as the hand.
 */
@Composable
fun LakeStage() {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val navigator = LocalSpatialNavigator.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val head = remember { HMDTrackingProvider() }
    val hands = remember { HandTrackingProvider() }
    /** Set when the stage is disposed: frames and posts already queued do nothing (post-quit safety). */
    val gone = remember { booleanArrayOf(false) }
    val handPlay = remember { HandPlay(post = { f -> main.post { if (!gone[0]) runCatching(f).onFailure { Log.w(TAG, "posted: ${it.message}") } } }) }
    val subscription = remember { arrayOfNulls<Cancellable>(1) }
    var view by remember { mutableStateOf<LakeView?>(null) }
    val seat = remember { Seat() }
    val panels = remember { HashMap<String, Entity>() }
    val contentRef = remember { arrayOfNulls<SpatialViewContent>(1) }
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

    /**
     * A system pinch-down on [target] (an entity of ours or not). The pinching hand's position from the event, as
     * given ([raw]) and converted from the view's global space ([conv]): whichever lands within reach of the eyes in
     * the seat's frame is used. [seatPos] (debug hook) is a position already in the seat's frame.
     */
    fun pinchDown(target: Entity?, raw: Vector3?, conv: Vector3?, seatPos: Vec3? = null, kind: String = "") {
        val v = view ?: return
        val id = v.hitOf(target)
        val rawS = raw?.let { seat.toLocal(it) }
        val convS = conv?.let { seat.toLocal(it) }
        val eye = Vec3(0.0, Layout.EYE, 0.0)
        val at = seatPos ?: listOfNotNull(convS, rawS).firstOrNull { it.distanceTo(eye) < 1.0 }
        Log.i(INPUT_TAG, "pinch down on ${id ?: "-"} $kind raw=${raw?.let { "(%.2f, %.2f, %.2f)".format(it.x, it.y, it.z) }} -> seat ${fmt(rawS)}; conv -> seat ${fmt(convS)}; used ${fmt(at)}${if (seatPos != null) " (debug)" else ""}")
        handPlay.pinch(id, at)
    }

    /** A drag's move (px, view axes) into metres in the seat's frame. */
    fun dragDelta(dx: Double, dy: Double, dz: Double): Vec3 {
        val f = Math.toRadians(seat.heading.toDouble())
        return Vec3(dx * cos(f) - dz * sin(f), -dy, dx * sin(f) + dz * cos(f))
    }

    com.vrgames.fishing.platform.AppExit.TrackStage()
    DisposableEffect(Unit) {
        Game.init(context)
        com.vrgames.fishing.platform.AppExit.stageAlive = true
        gone[0] = false
        Game.closeWindow = { id ->
            navigator.closeWindowContainer(id)
            Game.windowGone(id)
        }
        Game.dropNet = { handPlay.dropNet() }
        Game.crankBy = { turns -> handPlay.addCrank(turns) }
        Game.voice = Voice(context)
        val hd = runCatching { head.start() }
        Log.i(TAG, "head tracking ${hd.getOrNull() ?: hd.exceptionOrNull()?.message}")
        val hs = runCatching { hands.start() }
        Game.handsOn = hs.isSuccess && runCatching { hands.supportState.toString() }.getOrNull()?.contains("SUPPORT", ignoreCase = true) != false
        Log.i(INPUT_TAG, "hand tracking ${hs.getOrNull() ?: hs.exceptionOrNull()?.message} support=${runCatching { hands.supportState }.getOrNull()} stamp getter=${stampGetter != null}")
        onDispose {
            // Post-quit safety: frames already queued must not touch what is gone.
            gone[0] = true
            com.vrgames.fishing.platform.AppExit.stageAlive = false
            runCatching { head.stop() }
            runCatching { hands.stop() }
            subscription[0]?.cancel()
            Game.voice?.stop()
            Game.voice = null
            Game.dropNet = {}
            Game.crankBy = {}
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
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) music.resume() else if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { music.pause(); Game.voice?.stop() }
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
    // Debug hook `replace`: measure the eyes again (the head reads all zeros for Game.debugZeroHeadS seconds first).
    LaunchedEffect(Game.placeRequest) { if (Game.placeRequest > 0) seat.remeasure(Game.debugZeroHeadS) }
    // Debug hook `gaze`: a gaze-and-pinch on a target of ours through the same handler as the system's events, with
    // the simulated hand's position when the test's hands are on (the right hand; the left one for the net).
    LaunchedEffect(Game.debugGaze) {
        val g = Game.debugGaze ?: return@LaunchedEffect
        Game.debugGaze = null
        val v = view ?: return@LaunchedEffect
        val e = v.hitEntity(g)
        Log.i(AutoPilot.TAG, "gaze $g -> entity ${if (e != null) "found" else "none"}")
        pinchDown(e, null, null, seatPos = handPlay.simPinchPoint(left = g == "net"), kind = "(debug gaze $g)")
    }
    // Debug hook `drag`: a system drag through the same handlers ("cast": the rod pinch-held and thrown in two steps
    // with no hand tracking; "crank": a gaze-pinch-hold on the reel circled). Fed as timed moves (the emulator is slow).
    LaunchedEffect(Game.debugDrag) {
        val kind = Game.debugDrag ?: return@LaunchedEffect
        Game.debugDrag = null
        val v = view ?: return@LaunchedEffect
        // Timed from now on (a drag's moves never go back in time).
        val t0 = SystemClock.uptimeMillis() / 1000.0 + 0.01
        when (kind) {
            "cast" -> {
                pinchDown(v.hitEntity("rod"), null, null, kind = "(debug drag cast)")
                handPlay.dragStart("rod", t0)
                // Up 20 cm and back 12 cm in 0.5 s, still 0.2 s, then forward 35 cm in 0.28 s (smooth), stop.
                var p = Vec3.ZERO
                fun to(q: Vec3, from: Double, dur: Double, steps: Int) {
                    val a = p
                    for (k in 1..steps) {
                        val u = k.toDouble() / steps
                        val s = u * u * u * (10 - 15 * u + 6 * u * u)
                        val n = a * (1 - s) + q * s
                        handPlay.dragMove(n - p, t0 + from + dur * u)
                        p = n
                    }
                }
                to(Vec3(0.0, 0.20, 0.12), 0.0, 0.5, 40)
                to(Vec3(0.0, 0.20, 0.121), 0.5, 0.2, 10)
                to(Vec3(-0.04, -0.02, -0.23), 0.7, 0.28, 28)
                to(Vec3(-0.04, -0.02, -0.231), 0.98, 0.15, 8)
                handPlay.dragEnd()
            }
            "crank" -> {
                pinchDown(v.hitEntity("reel"), null, null, kind = "(debug drag crank)")
                handPlay.dragStart("reel", t0)
                var p = Vec3.ZERO
                for (k in 1..300) {
                    val a = k * 2 * Math.PI / 75.0
                    val q = Vec3(0.06 * cos(a) - 0.06, 0.06 * sin(a), 0.0)
                    handPlay.dragMove(q - p, t0 + k / 100.0)
                    p = q
                }
                handPlay.dragEnd()
            }
        }
    }
    LaunchedEffect(Game.noteVersion) {
        val n = Game.note ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.length > 40) 9000 else 6000)
        if (Game.note == n) Game.note = null
    }
    // v0.5.1: exactly the panels core.PanelLayout.shown lists for now (tested there: no two up together overlap, none
    // hides the rod, the net, the bait box, the float's water or the fish); each one also draws its content only then.
    val shownNow = Game.panelsShown()
    LaunchedEffect(shownNow, view) {
        fun show(id: String, on: Boolean) = panels[id]?.let { if (it.enabled != on) it.enabled = on }
        for (id in listOf(PANEL_CARD, PANEL_INTRO, PANEL_RESULT, PANEL_HUD, PANEL_NOTE, PANEL_TUTOR, PANEL_MENU, PANEL_MENU_BTN)) show(id, shownNow.any { it.id == id })
        // The board 6° under the eyes; lifted 10° over them while the float or the fish is out on the water.
        shownNow.firstOrNull { it.id == PANEL_TUTOR }?.let { slot ->
            panels[PANEL_TUTOR]?.let { e ->
                e.setPos(slot.at.toVector3())
                e.transform().eulerAngles = EulerAngles(slot.eulerPitch, slot.eulerYaw, 0f)
            }
        }
        com.vrgames.fishing.core.PanelProbe.shown = shownNow
        Log.i(TAG, "panels up: ${shownNow.joinToString(" ") { it.id + if (it === com.vrgames.fishing.core.PanelLayout.TUTOR_HIGH) "(high)" else "" }}")
    }
    LaunchedEffect(Game.debugOpen) {
        when (Game.debugOpen ?: return@LaunchedEffect) {
            "log" -> if (Game.window != LOG_ID) toggleWindow(LOG_ID)
            "map" -> if (Game.window != MAP_ID) toggleWindow(MAP_ID)
            "menu" -> Game.menuOpen = true
            "menuoff" -> Game.menuOpen = false
            "close" -> Game.window?.let { Game.closeWindow(it) }
        }
        Game.debugOpen = null
    }

    SpatialView(
        Modifier
            // Every finger-down: what it lands on and where the pinching hand was (core.Angler decides).
            .pointerInput(Unit) {
                detectSpatialPointerEvent(context, TargetEntity.any()) { infos ->
                    for (i in infos) {
                        if (!i.isDownEvent()) continue
                        val raw = runCatching { i.inputDevicePose.rawPosition }.getOrNull()?.let { Vector3(it.x, it.y, it.z) }
                        val c = contentRef[0]
                        val conv = if (raw == null || c == null) null else runCatching { c.convertPosition(raw, ViewCoordinateSpace.Global, c.localSpatialCoordinateSpace) }.getOrNull()
                        runCatching { pinchDown(i.targetedEntity, raw, conv, kind = "kind=${runCatching { i.kind }.getOrNull()}") }
                            .onFailure { Log.w(INPUT_TAG, "pinch: ${it.message}") }
                    }
                    false
                }
            }
            // A pinch held and moved: the rod with no hand tracking; the reel's crank (any time, hands or not).
            .pointerInput(1) {
                fun meters(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }.toDouble()
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, value -> handPlay.dragStart(view?.hitOf(value.targetEntity)) },
                    onDragEnd = { handPlay.dragEnd() },
                    onDragCancel = { handPlay.dragEnd() },
                ) { value ->
                    handPlay.dragMove(dragDelta(meters(value.dragAmount.x), meters(value.dragAmount.y), meters(value.dragAmount.z)))
                }
            },
        attachments = {
            // Each panel draws its content only while it is up (2026-11-10: hiding the entity alone let an old panel
            // come back when its content recomposed).
            AttachmentPanel(PANEL_CARD) { if (Game.panelOn(PANEL_CARD)) CardPanel(converter) }
            AttachmentPanel(PANEL_INTRO) { if (Game.panelOn(PANEL_INTRO)) IntroPanel(converter) }
            AttachmentPanel(PANEL_HUD) { if (Game.panelOn(PANEL_HUD)) HudPanel(converter, toggleWindow) }
            AttachmentPanel(PANEL_RESULT) { if (Game.panelOn(PANEL_RESULT)) ResultPanel(converter, toggleWindow) }
            AttachmentPanel(PANEL_NOTE) { if (Game.panelOn(PANEL_NOTE)) NotePanel(converter) }
            AttachmentPanel(PANEL_TUTOR) { if (Game.panelOn(PANEL_TUTOR)) TutorialPanel(converter) }
            AttachmentPanel(PANEL_MENU_BTN) { if (Game.panelOn(PANEL_MENU_BTN)) MenuButton(converter) }
            AttachmentPanel(PANEL_MENU) {
                if (Game.panelOn(PANEL_MENU)) MenuPanel(converter, toggleWindow, faceMe = { Game.faceRequest++ }, quit = { com.vrgames.fishing.platform.AppExit.finishAll(context) }, version = appVersion)
            }
        },
        update = { _, attachments -> view?.let { attach(it, attachments, panels) } },
        initial = { content, attachments ->
            contentRef[0] = content
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
                if (gone[0]) return@subscribe
                runCatching {
                    val dt = e.deltaTime.toDouble().coerceIn(0.0, 0.1) * (if (AutoPilot.debug) Game.debugTimeScale else 1.0)
                    val pose = runCatching { if (head.state == DataProvider.State.STARTED) head.latestData?.hmdPose else null }.getOrNull()
                    seat.frame(pose?.position, pose?.rotation, dt)
                    main.post { if (!gone[0]) runCatching { Game.tick(dt) }.onFailure { Log.w(TAG, "tick: ${it.message}") } }
                    // The hands (or, debug builds, the test's simulated hands): the rod, the reel, the net.
                    val trip = Game.trip
                    val data = runCatching { if (hands.state == DataProvider.State.STARTED) hands.latestData else null }.getOrNull()
                    val r = reading(data?.right, seat)
                    val l = reading(data?.left, seat)
                    synchronized(Game.lock) {
                        handPlay.frame(dt, r, l, stampOf(data), if (AutoPilot.debug) Game.simScenario else null, v, trip, Game.phase == Game.Phase.PLAYING)
                        trip?.let { v.sync(it, Game.aimYaw) }
                    }
                    v.frame(dt)
                }.onFailure { Log.w(TAG, "frame: ${it.message}") }
            }
        },
    )
}

private fun fmt(p: Vec3?) = if (p == null) "-" else "(%.2f, %.2f, %.2f)".format(p.x, p.y, p.z)

/** Textures and models loaded if present (see docs/素材来源.md). */
private val MODELS = listOf("sky", "rod", "baitbox", "boat", "raft", "crucian", "bream", "bighead", "silver", "culter", "mandarin", "catfish", "carp", "koi", "basket", "willow", "rail", "cormorant", "hat", "icetent", "auger", "karst")

/**
 * v0.5.1: every panel where core.PanelLayout puts it (facing the eye; the scene is placed from the measured eyes, so
 * these stand the same way from the eyes on any headset).
 */
private fun attach(v: LakeView, a: SpatialViewAttachments, panels: HashMap<String, Entity>) {
    fun put(slot: com.vrgames.fishing.core.PanelLayout.Slot) {
        val e = a.entity(slot.id) ?: return
        if (panels[slot.id] === e) return
        panels[slot.id] = e
        v.root.addChild(e)
        e.setPos(slot.at.toVector3())
        e.transform().eulerAngles = EulerAngles(slot.eulerPitch, slot.eulerYaw, 0f)
    }
    val pl = com.vrgames.fishing.core.PanelLayout
    put(pl.CARD_SLOT)
    put(pl.INTRO_SLOT)
    put(pl.RESULT_SLOT)
    put(pl.HUD_SLOT)
    put(pl.NOTE_SLOT)
    put(pl.MENU_SLOT)
    put(pl.MENU_BTN_SLOT)
    // The tutorial's board in front, 6° under the eyes 1 m out (moved up while the float or fish is out there).
    put(pl.TUTOR_LOW)
}

/** The tracker's own timestamp of its data (internal in the SDK, read by reflection): which readings are new. */
private val stampGetter by lazy { runCatching { HandTrackingData::class.java.getMethod("getTimestamp\$tracking_release") }.getOrNull() }
private fun stampOf(h: HandTrackingData?): Long? = h?.let { d -> runCatching { stampGetter?.invoke(d) as? Long }.getOrNull() }

/**
 * One tracked hand in the seat's frame (null when it is not tracked): the palm and the fingertips' POSITIONS only
 * (v0.5: no pinch strength, no hand orientation).
 */
private fun reading(h: HandPose?, seat: Seat): HandReading? {
    if (h == null) return null
    fun j(i: HandJoint.Index): Vec3? = runCatching { h.joint(i)?.position }.getOrNull()?.let { seat.toLocal(it) }
    val palm = j(HandJoint.Index.PALM) ?: return null
    return HandReading(
        palm = palm,
        wrist = j(HandJoint.Index.WRIST),
        indexTip = j(HandJoint.Index.INDEX_TIP),
        thumbTip = j(HandJoint.Index.THUMB_TIP),
        middleTip = j(HandJoint.Index.MIDDLE_TIP),
        ringTip = j(HandJoint.Index.RING_TIP),
    )
}

/**
 * Where the player sits; turned once to the head's mean heading; tracks the aim (head yaw). v0.4: placed at a height
 * from the measured eyes (core.EyeHeight: steady, valid head readings; a seated eye if none come), so the eye is at
 * [LakeView.EYE] in the scene and the rod's holder, the reel and the net sit at the same height below the eyes on any
 * headset, whatever the room origin (2026-11-19: heights from the room origin or the first pose broke other games).
 */
private class Seat {
    private var ox = 0f
    private var oz = 0f
    private var floorY = 0f
    private var eyes = com.vrgames.fishing.core.EyeHeight()
    @Volatile private var remeasureZeroS = -1.0
    private var zeroUntil = 0L

    /** Debug hook `replace`: measure again, the head reading zeros for [zeroS] seconds (as a headset may at start). */
    fun remeasure(zeroS: Double) { remeasureZeroS = zeroS }

    /** A tracked point into the seat's frame (the scene's root: under the eyes, turned to [heading]). */
    fun toLocal(p: Vector3): Vec3 {
        val dx = (p.x - ox).toDouble(); val dz = (p.z - oz).toDouble()
        val a = Math.toRadians(facing.toDouble())
        // The root is turned by +facing about y; undo it.
        return Vec3(dx * cos(a) - dz * sin(a), (p.y - floorY).toDouble(), dx * sin(a) + dz * cos(a))
    }
    var root: Entity? = null
    private var placed = false
    private var facing = 0f
    /** Which way the seat faces (degrees about y). */
    val heading get() = facing
    private val headings = ArrayList<Float>()
    private var started = 0L

    fun again() {
        placed = false
        headings.clear()
    }

    fun frame(eye0: Vector3?, rot0: Quat?, dt: Double) {
        Game.debugLook?.let { Game.aimYaw = it }
        val z = remeasureZeroS
        if (z >= 0) {
            remeasureZeroS = -1.0
            eyes = com.vrgames.fishing.core.EyeHeight()
            zeroUntil = android.os.SystemClock.uptimeMillis() + (z * 1000).toLong()
            placed = false
            headings.clear()
            Log.i(TAG, "replace: measuring the eyes again, zeros for $z s")
        }
        val zero = android.os.SystemClock.uptimeMillis() < zeroUntil
        val eye = if (zero) Vector3(0f, 0f, 0f) else eye0
        val rot = if (zero) null else rot0
        if (eyes.feed(eye?.x, eye?.y, eye?.z, dt)) {
            floorY = eyes.value!! - LakeView.EYE.toFloat()
            Log.i(TAG, "eyes at ${eyes.value} (${if (eyes.guessed) "fallback" else "measured"}): scene floor at $floorY, rod grip at ${eyes.value!! - (Layout.EYE - Layout.REST_GRIP.y).toFloat()}")
            if (placed) root?.setPos(Vector3(ox, floorY, oz))
        }
        if (rot == null || eye == null) return
        if (!placed && (eyes.value == null || !com.vrgames.fishing.core.EyeHeight.valid(eye.x, eye.y, eye.z))) return
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
            it.setPos(Vector3(eye.x, floorY, eye.z))
            ox = eye.x; oz = eye.z
            it.transform().eulerAngles = EulerAngles(0f, facing, 0f)
        }
        Log.i(TAG, "seat placed: facing $facing, eye $eye, floor $floorY")
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
