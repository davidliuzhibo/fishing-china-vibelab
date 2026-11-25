package com.vrgames.fishing.render

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.ImageBasedLightComponent
import com.pico.spatial.core.ecs.ImageBasedLightReceiverComponent
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.vrgames.fishing.core.OverheadCast
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lake
import com.vrgames.fishing.core.Layout
import com.vrgames.fishing.core.Trip
import com.vrgames.fishing.core.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A fishing place around the seated player (full immersion). Frame: the player's feet at the
 * origin, facing -z, the water at y = 0 (the seat is on a bank, a boat, a raft or the ice, a little
 * above it). The panorama all round, the water near the player, the rod held in front with its
 * line to the float, the reel to turn ("reel"), the bait box ("bait"), the net ("net"), the catch
 * basket, and the fish when it fights and when it is landed.
 */
class LakeView(private val models: Map<String, Entity>, private val tex: Map<String, TextureResource>) {
    val root = Entity()
    private var placeRoot = Entity().also { root.addChild(it) }
    private val hits = HashMap<Entity, String>()
    /** One entity per pinch target (debug hook `gaze`: a pinch delivered as if on it). */
    private val hitEntities = HashMap<String, Entity>()
    private val sphere = MeshResource.createSphere(1f).shared()
    private val splash = Sparks("splash", 200, sphere, pbr(color(0.9f, 0.95f, 1f, 0.8f), roughness = 0.2f, blending = BlendingMode.TRANSPARENT).shared()).also { root.addChild(it.entity) }
    private val sparkle = Sparks("sparkle", 120, sphere, glow(color(1f, 0.85f, 0.4f, 1f)).shared()).also { root.addChild(it.entity) }
    private val iblSource = Entity().also { root.addChild(it) }
    private var sky: Entity? = null
    private var water: Entity? = null
    private var water2: Entity? = null
    // v0.3: the rod, reel, line and net as moving tackle (render/Tackle), and rings on the water (PICO 1117/1118).
    private val tackle = Tackle(root)
    private val rings = Ripples(root)
    /** v0.6.0: the place's near, middle and far things, and what moves on its own (render/Scenery). */
    private val scenery = Scenery(root, models, tex,
        { p -> splash.emit(p, 10, 0.5, 0.6, 0.014f, Vec3(0.0, 0.45, 0.0)) },
        { p, s -> rings.spawn(p, 2, s, 2.0) })
    private val net = Entity().also { root.addChild(it) }
    /**
     * v0.5 (device test 2026-11-20: the net jumped into a resting hand while the fish was still 6–10 m out): the net is
     * a see-through ghost with NO collider until the fish can be netted; then the solid net (two slim colliders, the
     * pole and the hoop, clear of the panels' and the bait box's sight lines) with its glow.
     */
    private val netSolid = Entity()
    private val netGhost = Entity()
    private val poleNode = Entity()
    private val hoopNode = Entity()
    private var netActive = true
    /** Set by the stage each frame from the hands (null: not in a hand). */
    var handGrip: Vec3? = null
    var handDir: Vec3? = null
    /** v0.6.0: the rod is kept in the hand while waiting or fighting: it points from the hand at the float or the fish. */
    var handAims = false
    var netHand: Vec3? = null
    var netHoop: Vec3? = null
    /** A cast's whip this frame (degrees), from the stage. */
    var whip = 0.0
    /** v0.5: radians the reel's crank has turned (from any winding). */
    var crankSpin = 0.0
    private var shownSpin = 0.0
    private var kickT = -1.0
    /** v0.4: hand tracking is the way in (the grip glows to be taken); a hand near the grip; a hand at the reel. */
    var handsMode = false
    var gripNear = false
    var crankNear = false
    /** v0.4: the next cast's launch angle (degrees), set before the cast, for the float's flight. */
    var launchDeg = 30.0
    /** v0.4: counts the float's landings (the hands put the rod back in its holder on the next one). */
    var landings = 0; private set
    private var lastState: Trip.State? = null
    private var flight = -1.0
    private var flightS = FLIGHT_S
    private var flightArc = 0.5
    private var flightFrom = Vec3.ZERO
    private var flightTo = Vec3.ZERO
    private var floatAt = Vec3.ZERO
    private var bobT = 0.0
    private var fishRingT = 0.0
    private val float = Entity().also { root.addChild(it) }
    private val fishHolder = Entity().also { root.addChild(it) }
    private var fishModel: Entity? = null
    private var fishId: String? = null
    private val shown = Entity().also { root.addChild(it) }
    private var shownT = -1.0
    private val ripples = ArrayList<Pair<Entity, Double>>()
    private val basket = Entity().also { root.addChild(it) }
    private var clock = 0.0
    private var bend = 0f

    private val stone = pbr(color(0.55f, 0.55f, 0.52f), roughness = 0.9f).shared()
    private val wood = pbr(color(0.45f, 0.32f, 0.2f), roughness = 0.7f).shared()
    private val iceMat = pbr(color(0.85f, 0.92f, 0.98f), roughness = 0.25f).shared()
    private val dark = pbr(color(0.03f, 0.06f, 0.08f), roughness = 0.1f).shared()
    private val red = pbr(color(0.9f, 0.15f, 0.1f), roughness = 0.4f).shared()
    private val white = pbr(color(0.95f, 0.95f, 0.95f), roughness = 0.4f).shared()
    private val rodMat = pbr(color(0.18f, 0.12f, 0.08f), roughness = 0.4f).shared()
    private val chrome = pbr(color(0.8f, 0.82f, 0.85f), metallic = 0.9f, roughness = 0.25f).shared()

    /**
     * The landing net, made here (an AI model cannot make a net's open mesh): a wooden handle, an aluminium hoop, and a
     * bag of green netting (a see-through diamond mesh, both sides drawn). Its handle runs along +y from the end at 0;
     * the hoop's centre at 0.79. v0.5: the pole and the hoop each carry their own slim collider and hover glow.
     */
    private fun buildNet() {
        val handleMat = pbr(color(0.45f, 0.3f, 0.18f), roughness = 0.6f)
        val gripMat = pbr(color(0.1f, 0.1f, 0.1f), roughness = 0.9f)
        poleNode.addChild(model(MeshResource.createCylinder(0.62f, 0.011f), handleMat).apply { setPos(Vector3(0f, 0.31f, 0f)) })
        poleNode.addChild(model(MeshResource.createCylinder(0.1f, 0.013f), gripMat).apply { setPos(Vector3(0f, 0.08f, 0f)) })
        poleNode.addChild(netGripGlow)
        val b = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val p = android.graphics.Paint().apply { isAntiAlias = true; color = 0xFF2F6B3A.toInt(); strokeWidth = 3f }
        for (k in -16..32) {
            cv.drawLine(k * 16f, 0f, k * 16f + 256f, 256f, p)
            cv.drawLine(k * 16f, 256f, k * 16f + 256f, 0f, p)
        }
        val mesh = UnlitMaterial.create(BlendingMode.MASKED).apply {
            setBaseColorTexture(TextureResource.create(b))
            setOpacityThreshold(0.4f)
            setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE)
        }
        hoopNode.setPos(Vector3(0f, 0.79f, 0f))
        // The hoop in the handle's plane (as a real landing net), so a held net scoops with its mouth up.
        hoopNode.addChild(model(MeshResource.createTorus(0.17f, 0.158f), chrome).apply { transform().eulerAngles = EulerAngles(90f, 0f, 0f) })
        // The bag hangs below the hoop: a cone, point down.
        hoopNode.addChild(model(MeshResource.createCone(0.24f, 0.165f), mesh).apply { setPos(Vector3(0f, 0f, -0.12f)); transform().eulerAngles = EulerAngles(-90f, 0f, 0f) })
        hoopNode.addChild(netGlow)
        hitBox(poleNode, "net", Layout.NET_POLE_SIZE.toVector3(), Layout.NET_POLE_C.toVector3())
        hitBox(hoopNode, "net", Layout.NET_HOOP_SIZE.toVector3(), Vector3(0f, 0f, 0f))
        netSolid.addChild(poleNode)
        netSolid.addChild(hoopNode)
        // The ghost: the same shapes, faint and see-through, nothing to pinch.
        val ghost = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply { setBaseColor(color(0.8f, 0.88f, 0.9f, 1f)); setOpacity(0.22f); setDepthWrite(false) }.shared()
        netGhost.addChild(model(MeshResource.createCylinder(0.62f, 0.011f), ghost).apply { setPos(Vector3(0f, 0.31f, 0f)) })
        netGhost.addChild(model(MeshResource.createTorus(0.17f, 0.158f), ghost).apply { setPos(Vector3(0f, 0.79f, 0f)); transform().eulerAngles = EulerAngles(90f, 0f, 0f) })
        netGhost.addChild(model(MeshResource.createCone(0.24f, 0.165f), ghost).apply { setPos(Vector3(0f, 0.79f, -0.12f)); transform().eulerAngles = EulerAngles(-90f, 0f, 0f) })
        net.addChild(netSolid)
        net.addChild(netGhost)
    }

    /** v0.5: the net usable (solid, collider, glow) only while [on]; else a ghost far from any pinch. */
    private fun netUsable(on: Boolean) {
        if (netActive == on) return
        netActive = on
        netSolid.enabled = on
        // Moved far below as well as disabled, so no collider of it can take a pinch meant for something else.
        netSolid.setPos(if (on) Vector3(0f, 0f, 0f) else Vector3(0f, -100f, 0f))
        netGhost.enabled = !on
    }

    private val netGripGlow = model(MeshResource.createCylinder(1f, 1f), rim(color(1f, 0.85f, 0.35f, 0.9f)).shared()).apply {
        setPos(Vector3(0f, 0.1f, 0f))
        transform().setScaleVector(Vector3(0.022f, 0.2f, 0.022f))
        enabled = false
    }

    private val netGlow = model(MeshResource.createTorus(0.2f, 0.186f), glow(color(1f, 0.85f, 0.35f, 0.9f)).shared()).apply {
        transform().eulerAngles = EulerAngles(90f, 0f, 0f)
        enabled = false
    }
    private var glowT = 0.0

    /** v0.4: the rod's holder (a post with a Y fork) the rod rests in and pivots on, right in front of the player. */
    private val holder = Entity().also { root.addChild(it) }

    init {
        // v0.5: the gaze-and-pinch targets ride on the tackle itself: a slim box along the cork and reel seat ("rod"),
        // a box round the reel ("reel"); each glows when looked at.
        hitBox(tackle.handleNode, "rod", Vector3(0.06f, 0.30f, 0.07f), Vector3(0f, 0.01f, -0.01f))
        hitBox(tackle.reelNode, "reel", Vector3(0.11f, 0.11f, 0.11f), Vector3(0f, 0.01f, -0.02f))
        holder.setPos(PIVOT)
        val post = PIVOT.y - 0.3
        holder.addChild(model(MeshResource.createCylinder(1f, 1f), wood).apply { setPos(Vector3(0f, (-0.045 - post / 2).toFloat(), 0f)); transform().setScaleVector(Vector3(0.012f, post.toFloat(), 0.012f)) })
        for (side in listOf(-1f, 1f)) holder.addChild(model(MeshResource.createCylinder(1f, 1f), wood).apply {
            setPos(Vector3(side * 0.0175f, -0.045f + 0.03f, 0f))
            transform().setScaleVector(Vector3(0.0055f, 0.07f, 0.0055f))
            transform().eulerAngles = EulerAngles(0f, 0f, -side * 30f)
        })
        holder.addChild(model(sphere, rodMat).apply { setPos(Vector3(0f, -0.045f, 0f)); setScale(0.011f) })
        // The float.
        float.addChild(model(MeshResource.createCapsule(0.06f, 0.012f), red).apply { setPos(Vector3(0f, 0.02f, 0f)) })
        float.addChild(model(MeshResource.createCapsule(0.03f, 0.013f), white).apply { setPos(Vector3(0f, -0.01f, 0f)) })
        // The bait box at the left, the net on its rest at the right.
        val bait = Entity().apply { setPos(Layout.BAIT.toVector3()) }
        (models["baitbox"]?.clone(Entity.CloneOptions(true, true))?.also { it.setScale(0.12f) } ?: model(MeshResource.createBox(Vector3(0.18f, 0.06f, 0.12f), 0.01f), wood)).let { bait.addChild(it) }
        hit(bait, "bait", Vector3(0.2f, 0.12f, 0.16f))
        root.addChild(bait)
        hitEntities["bait"] = bait
        buildNet()
        netUsable(false)
        restNet()
        // Everything at the side stays half a metre out, clear of the seated body.
        basket.setPos(Layout.BASKET.toVector3())
        // A woven bamboo creel (SGS model, 2026-11-12); a plain tub until it loads. It stays the first child.
        basket.addChild(models["basket"]?.clone(Entity.CloneOptions(true, true))?.apply { setScale(0.3f); setPos(Vector3(0f, -0.08f, 0f)) }
            ?: model(MeshResource.createCylinder(0.16f, 0.12f), pbr(color(0.62f, 0.5f, 0.3f), roughness = 0.9f)))
        fishHolder.enabled = false
        shown.enabled = false
    }

    private fun hit(e: Entity, id: String, size: Vector3) = hitBox(e, id, size, Vector3(0f, 0f, 0f))

    /** A pinch target: a box of [size] (full) centred [at] in [e]'s frame; [e] holds the models that glow when looked at. */
    private fun hitBox(e: Entity, id: String, size: Vector3, at: Vector3) {
        e.components.set(CollisionComponent(listOf(ShapeResource.createBox(size).offsetByTranslation(at)), PhysicsMaterialResource()))
        e.components.set(InteractableComponent())
        e.components.set(HoverEffectComponent())
        hits[e] = id
        hitEntities.getOrPut(id) { e }
    }

    fun hitEntity(id: String): Entity? = hitEntities[id]

    /** The invisible wall ahead that takes pinches over the water. */
    fun registerWater(e: Entity) {
        hits[e] = "water"
        hitEntities["water"] = e
    }

    fun hitOf(e: Entity?): String? {
        var cur = e
        while (cur != null) {
            hits[cur]?.let { return it }
            cur = cur.getParent()
        }
        return null
    }

    // ---- A place ----

    fun build(l: Lake) {
        placeRoot.destroy()
        placeRoot = Entity().also { root.addChild(it) }
        sky?.destroy()
        val skyTex = tex["sky_${l.sky}"]
        sky = (models["sky"]?.clone(Entity.CloneOptions(true, true)))?.also { s ->
            s.setScale(SKY_R / 12f)
            s.setPos(Vector3(0f, 1.2f, 0f))
            if (skyTex != null) setMaterials(s, UnlitMaterial.create().apply { setBaseColorTexture(skyTex) })
            root.addChild(s)
        }
        // Water (or the ice) close by; far off the panorama shows it.
        water?.destroy()
        val wm: Material = if (l.ice) iceMat else PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
            val t = tex["water"]
            // v0.6.0: the moonlit lake's water dark (it was the daytime's bright teal under a night sky, emulator 2026-11-25).
            val night = l.id == "night"
            if (t != null) { setBaseColorTexture(t); setEmissiveTexture(t); setEmissiveColor(if (night) color(0.035f, 0.045f, 0.06f) else color(0.2f, 0.22f, 0.24f)) }
            setBaseColor(if (night) color(0.08f, 0.12f, 0.17f, 0.9f) else color(0.35f, 0.5f, 0.55f, 0.82f))
            setRoughness(0.08f)
            setMetallic(0.2f)
        }
        water = model(MeshResource.createCylinder(0.02f, WATER_R), wm).also { it.setPos(Vector3(0f, -0.01f, 0f)); root.addChild(it) }
        // v0.3 (PICO 1118: 「水面有波动」): a second, finer layer of glints turning the other way over the first, so the
        // surface shimmers and moves instead of sliding as one sheet.
        water2?.destroy()
        water2 = if (l.ice) null else tex["water"]?.let { t ->
            model(MeshResource.createCylinder(0.004f, WATER_R * 0.6f), UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColorTexture(t); setBaseColor(if (l.id == "night") color(0.5f, 0.55f, 0.7f, 1f) else color(0.85f, 0.95f, 1f, 1f)); setOpacity(if (l.id == "night") 0.1f else 0.18f); setDepthWrite(false)
            }).also { it.setPos(Vector3(0f, 0.004f, 0f)); root.addChild(it) }
        }
        when (l.id) {
            "xihu", "night" -> {
                // A stone embankment under the seat.
                placeRoot.addChild(model(MeshResource.createBox(Vector3(3f, 0.5f, 1.6f), 0.02f), stone).apply { setPos(Vector3(0f, 0.1f, 0.6f)) })
                for (k in 0 until 6) placeRoot.addChild(model(MeshResource.createBox(Vector3(0.5f, 0.06f, 0.3f), 0.01f), stone).apply { setPos(Vector3(-1.25f + k * 0.5f, 0.38f, -0.15f)) })
                // 2026-11-12 (review: the scene was rough): willows either side, stone railings at the ends.
                prop("willow", 3.2f, -1.7f, 0.35f, 0.1f, 20f)
                prop("willow", 2.6f, 1.9f, 0.35f, 0.4f, -40f)
                prop("rail", 1.1f, -1.25f, 0.41f, 0.35f, 90f)
                prop("rail", 1.1f, 1.25f, 0.41f, 0.35f, -90f)
            }
            "qiandao" -> models["boat"]?.clone(Entity.CloneOptions(true, true))?.let { b -> b.setScale(1.4f); b.setPos(Vector3(0f, -0.15f, 0.3f)); b.transform().eulerAngles = EulerAngles(0f, 90f, 0f); placeRoot.addChild(b) }
                ?: placeRoot.addChild(model(MeshResource.createBox(Vector3(1.2f, 0.3f, 3f), 0.1f), wood).apply { setPos(Vector3(0f, 0.05f, 0.4f)) })
            "lijiang" -> {
                models["raft"]?.clone(Entity.CloneOptions(true, true))?.let { b -> b.setScale(0.9f); b.setPos(Vector3(0f, -0.05f, 0.3f)); b.transform().eulerAngles = EulerAngles(0f, 90f, 0f); placeRoot.addChild(b) }
                    ?: placeRoot.addChild(model(MeshResource.createBox(Vector3(1.2f, 0.12f, 3f), 0.03f), pbr(color(0.5f, 0.6f, 0.3f), roughness = 0.7f)).apply { setPos(Vector3(0f, 0.03f, 0.4f)) })
                for ((k, rp) in l.rocksAt.withIndex()) {
                    val (yaw, dist) = rp
                    val a = Math.toRadians(yaw)
                    val x = (dist * sin(a)).toFloat(); val z = (-dist * cos(a)).toFloat()
                    // Karst rocks (SGS model) where the mandarin fish hide; plain boulders until it loads.
                    if (!prop("karst", 1.8f + 0.4f * (k % 2), x, -0.25f, z, 37f * k)) placeRoot.addChild(model(sphere, stone).apply {
                        setPos(Vector3(x, 0.3f, z))
                        transform().setScaleVector(Vector3(1.4f, 0.8f, 1.1f))
                    })
                }
                // A cormorant on its pole and a bamboo hat on the raft.
                prop("cormorant", 0.55f, 0.75f, 0.1f, 0.15f, -30f)
                prop("hat", 0.42f, -0.55f, 0.13f, 0.45f, 15f)
            }
            else -> {
                // The ice hole in front.
                placeRoot.addChild(model(MeshResource.createCylinder(0.03f, 0.22f), dark).apply { setPos(Vector3(0.2f, 0.0f, -0.75f)) })
                placeRoot.addChild(model(MeshResource.createTorus(0.23f, 0.03f), iceMat).apply { setPos(Vector3(0.2f, 0.01f, -0.75f)) })
                // The ice camp: a windbreak tent behind at the left, the auger that cut the hole.
                prop("icetent", 1.9f, -1.7f, 0f, 0.6f, 35f)
                prop("auger", 1.0f, 0.75f, 0f, -0.35f, -20f)
            }
        }
        scenery.build(l)
        receive(root)
    }

    /**
     * Puts model [name] (1 m along its longest side, standing on its origin) at [size] meters, at
     * x, y, z turned [yaw] degrees; false when the model is not there (the caller may stand in for it).
     */
    private fun prop(name: String, size: Float, x: Float, y: Float, z: Float, yaw: Float): Boolean {
        val m = models[name]?.clone(Entity.CloneOptions(true, true)) ?: return false
        m.setScale(size)
        m.setPos(Vector3(x, y, z))
        m.transform().eulerAngles = EulerAngles(0f, yaw, 0f)
        placeRoot.addChild(m)
        return true
    }

    // ---- Each frame ----

    // The rod's pose: where it points follows what is happening (2026-11-10 review: after the cast the
    // rod's angle was wrong and never changed). v0.5: in a hand it is drawn from the cast's state (core.Angler).
    private var rodPitch = 32f
    private var rodYaw = 0f

    /** Where the rod's grip is now (in the holder or the hand). */
    fun rodGripNow() = tackle.grip
    fun rodDirNow() = tackle.dir
    /** Where a hand closes on the rod now (the cork's middle, where it glows). */
    fun rodGripTarget() = Layout.gripTarget(tackle.grip, tackle.dir)
    /** Where the net's handle rests (front left, in reach of the free left hand). */
    val restNetGrip: Vec3 = Layout.NET_GRIP
    fun reelCentre() = tackle.reelCentre()
    fun reelAxle() = tackle.reelAxle()
    fun crankZero() = tackle.crankZero()
    fun knobAt() = tackle.knobAt()
    fun turnReel(radians: Double) = tackle.turnReel(radians)
    fun setCrank(radians: Double, wound: Double) = tackle.setCrank(radians, wound)

    /**
     * The net on its rest (v0.4): front left, its handle's end toward the player at the left hand, the hoop lying out on
     * the water's edge, in sight while looking at the water.
     */
    private fun restNet() {
        val (x, d, z) = Layout.netBasis(Layout.NET_AXIS)
        net.setPos(Layout.NET_REST_BUTT)
        net.transform().quaternion = quatFromBasis(x, d, z)
        net.setScale(Layout.NET_SCALE.toFloat())
    }

    /** The tired fish's place for the net: up at the surface ahead, hauled in toward the bank as it is reeled (v0.5). */
    fun fishForNet(t: Trip): Vec3 = Layout.fishForNet(t.castYaw, t.distance)

    private fun floatPoint(t: Trip): Vec3 {
        val dist = if (t.lake.ice) 0.0 else t.castDist
        val a = Math.toRadians(t.castYaw)
        return if (t.lake.ice) Vec3(0.2, 0.0, -0.75) else Vec3(dist * sin(a), 0.0, -dist * cos(a) - 0.6)
    }

    /** The float, line, rod, the fighting fish, the net, the aim marker, from the trip's state. */
    fun sync(t: Trip, aimYaw: Double) {
        val st = t.state
        val canNet = st == Trip.State.NETTABLE
        netUsable(canNet || netHand != null)
        if (netGlow.enabled != canNet) netGlow.enabled = canNet
        if (canNet) { glowT += 0.016; val k = (1f + 0.12f * kotlin.math.sin(glowT * 6).toFloat()); netGlow.transform().setScaleVector(Vector3(k)) }
        val gripGlowOn = canNet && netHand == null
        if (netGripGlow.enabled != gripGlowOn) netGripGlow.enabled = gripGlowOn
        if (gripGlowOn) { val k = (1f + 0.25f * kotlin.math.sin(glowT * 6).toFloat()); netGripGlow.transform().setScaleVector(Vector3(0.022f * k, 0.2f, 0.022f * k)) }
        val waiting = st == Trip.State.WAIT || st == Trip.State.BITE
        val fightingNow = st == Trip.State.FIGHT || st == Trip.State.NETTABLE
        val ready = st == Trip.State.READY || st == Trip.State.LANDED
        // A cast just made: the float flies from where it hung to where it lands, on an arc from the launch angle; the
        // time in the air from the throw (v0.4; v0.3 always 0.85 s).
        if (lastState != null && lastState != st && st == Trip.State.WAIT && (lastState == Trip.State.READY || lastState == Trip.State.LANDED)) {
            flight = 0.0
            // v0.5 (device test 2026-11-20: the float started from behind the tip): it leaves from the rod's tip.
            flightFrom = tackle.tip
            flightTo = floatPoint(t)
            val d = flightFrom.distanceTo(flightTo)
            flightS = if (t.lake.ice) 0.6 else OverheadCast.flightSeconds(d, launchDeg)
            flightArc = if (t.lake.ice) 0.2 else (d * kotlin.math.tan(Math.toRadians(launchDeg.coerceIn(5.0, 75.0))) / 4).coerceIn(0.3, 4.0)
        }
        if ((st == Trip.State.FIGHT || st == Trip.State.NETTABLE) && waitingBefore(lastState)) kickT = 0.0
        if (st == Trip.State.BITE && lastState == Trip.State.WAIT) { rings.spawn(floatAt, 2, 0.9, 1.8, 0.9); splash.emit(floatAt, 8, 0.4, 0.5, 0.01f, Vec3(0.0, 0.3, 0.0)) }
        if (st == Trip.State.NETTABLE && lastState == Trip.State.FIGHT) splash.emit(fishForNet(t), 16, 0.8, 0.7, 0.016f, Vec3(0.0, 0.8, 0.0))
        lastState = st
        // The rod (v0.4): resting in its holder and pointing where things are (out ready to cast; at the float while
        // waiting; at the fish in the fight, bent by it); pivoted in the holder by a hand; or in the hand. It glides
        // between the holder and the hand (taken: 0.12 s; put back: 0.4 s).
        val targetBend = when (st) { Trip.State.FIGHT, Trip.State.NETTABLE -> (t.tension * 28).toFloat(); Trip.State.BITE -> 10f; else -> 0f }
        bend += (targetBend - bend) * 0.2f
        val fp = floatPoint(t)
        val fishAt = if (fightingNow) fishPlace(t, st) else fp
        // The strike: the rod jerks up a moment as the hook is set.
        val kick = if (kickT >= 0) { kickT += lastDt; if (kickT > 0.6) { kickT = -1.0; 0f } else (16 * kotlin.math.exp(-6 * kickT)).toFloat() } else 0f
        val wantPitch = when {
            waiting -> Layout.WAIT_PITCH.toFloat()
            fightingNow -> Layout.FIGHT_PITCH.toFloat()
            else -> Layout.READY_PITCH.toFloat()
        } + kick
        val wantYaw = if (waiting || fightingNow) Math.toDegrees(atan2(fishAt.x - PIVOT.x, -(fishAt.z - PIVOT.z))).toFloat() else 0f
        rodPitch += (wantPitch - rodPitch) * 0.18f
        rodYaw += (wantYaw - rodYaw) * 0.18f
        val restDir = run {
            val p = Math.toRadians(rodPitch.toDouble()); val y = Math.toRadians(rodYaw.toDouble())
            Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p))
        }
        val restAt = PIVOT - restDir * PIVOT_BACK
        val hg = handGrip
        // v0.6.0: a rod kept in the hand points from the hand where the rested rod would (at the float, the fish).
        val hd = if (hg != null && handAims && (waiting || fightingNow)) run {
            val yaw = Math.toRadians(atan2(fishAt.x - hg.x, -(fishAt.z - hg.z)))
            val p = Math.toRadians(rodPitch.toDouble())
            Vec3(sin(yaw) * cos(p), sin(p), -cos(yaw) * cos(p))
        } else handDir
        if (hg != null && hd != null) { lastHandGrip = hg; lastHandDir = hd }
        val step = lastDt.coerceIn(0.0, 0.1)
        blend = if (hg != null && hd != null) (blend + step / TAKE_S).coerceAtMost(1.0) else (blend - step / PUT_BACK_S).coerceAtLeast(0.0)
        val k = blend * blend * (3 - 2 * blend)
        val grip = restAt * (1 - k) + lastHandGrip * k
        val dir = (restDir * (1 - k) + lastHandDir * k).normalized().let { if (it == Vec3.ZERO) restDir else it }
        // Where the float is: hanging from the tip before a cast (swinging as the rod moves), flying, then on the water.
        val dip = when {
            st == Trip.State.BITE -> -0.08
            t.nibbleT > 0 -> -0.025 * sin(t.nibbleT * 25)
            st == Trip.State.FIGHT -> -0.05
            else -> 0.008 * sin(clock * 2.2)
        }
        val fishP = fishPlace(t, st)
        val toward = when { fightingNow -> fishP; waiting -> fp; else -> null }
        val load = when (st) { Trip.State.FIGHT, Trip.State.NETTABLE -> 0.25 + 0.75 * t.tension; Trip.State.BITE -> 0.3; Trip.State.WAIT -> 0.06; else -> 0.0 }
        tackle.pose(grip, dir, toward, load, whip)
        // The crank turns as the line is wound (from the hands' circles, a drag, or the 「收线」 button).
        if (crankSpin != shownSpin) { tackle.turnReel(crankSpin - shownSpin); shownSpin = crankSpin }
        // The glows: the grip while the rod is to be taken (or, with hands, lifted to strike), the reel's knob in the fight.
        val gripLevel = if (blend < 0.5 && (ready || (waiting && handsMode))) (if (gripNear) 2f else 1f) else 0f
        val knobLevel = if (fightingNow) (if (crankNear) 2.2f else 1f) else 0f
        tackle.glows(gripLevel, knobLevel, clock)
        val flying = flight >= 0
        val hanging = ready && !flying
        if (!hanging) pendOn = false
        floatAt = when {
            t.lake.ice && !ready -> fp + Vec3(0.0, dip, 0.0)
            flying -> {
                val u = (flight / flightS).coerceIn(0.0, 1.0)
                val arc = flightArc * 4 * u * (1 - u)
                flightFrom * (1 - u) + flightTo * u + Vec3(0.0, arc, 0.0)
            }
            ready -> pendulum(tackle.tip, step)
            fightingNow -> fishP
            else -> fp + Vec3(0.0, dip, 0.0)
        }
        val floatShown = !fightingNow
        if (float.enabled != floatShown) float.enabled = floatShown
        float.setPos(floatAt)
        // The line: tip to the float or the fish; slack sags, tension draws it straight; red when the fish runs.
        val end = if (fightingNow) fishP + Vec3(0.0, 0.03, 0.0) else floatAt + Vec3(0.0, 0.03, 0.0)
        val span = tackle.tip.distanceTo(end)
        val sag = when {
            fightingNow -> span * 0.06 * (1 - t.tension).coerceIn(0.0, 1.0)
            ready -> 0.0
            flying -> span * 0.02
            else -> span * 0.05
        }
        tackle.line(if (t.lake.ice && ready) null else end, sag, t.running > 0 && fightingNow)
        // Rings: the float nods; a nibble; the fish below; the tired fish thrashing at the surface.
        if (!flying && waiting && !t.lake.ice) {
            bobT += 0.016
            if (bobT > 2.6) { bobT = 0.0; rings.spawn(floatAt, 1, 0.35, 1.8, 0.45) }
            if (t.nibbleT > 0.45) rings.spawn(floatAt, 1, 0.5, 1.4, 0.7)
        }
        if (fightingNow && !t.lake.ice) {
            fishRingT += 0.016
            val every = if (st == Trip.State.NETTABLE) 0.35 else if (t.running > 0) 0.3 else 0.8
            if (fishRingT > every) { fishRingT = 0.0; rings.spawn(Vec3(fishP.x, 0.0, fishP.z), 1, if (st == Trip.State.NETTABLE) 0.6 else 0.9, 1.6, 0.75) }
            if (st == Trip.State.NETTABLE && (clock * 10).toInt() % 4 == 0) splash.emit(fishP, 3, 0.5, 0.5, 0.012f, Vec3(0.0, 0.5, 0.0))
        }
        // The fish in the fight: under the water, rising as it tires; at the surface when it can be netted.
        val fighting = fightingNow
        if (fighting) {
            val f = t.fish
            if (f != null && fishId != f.id) {
                fishModel?.destroy()
                fishModel = models[f.model]?.clone(Entity.CloneOptions(true, true))?.also { m -> m.setScale(sizeOf(t)); fishHolder.addChild(m) }
                fishId = f.id
            }
            fishHolder.setPos(fishP)
            fishHolder.transform().eulerAngles = EulerAngles((10 * sin(clock * 9)).toFloat(), Math.toDegrees(atan2(fishP.x, fishP.z)).toFloat() + (25 * sin(clock * 6)).toFloat(), if (st == Trip.State.NETTABLE) (35 * sin(clock * 7)).toFloat() else 0f)
            if (t.running > 0 && (clock * 10).toInt() % 3 == 0) splash.emit(Vec3(fishP.x, 0.0, fishP.z), 4, 0.6, 0.6, 0.02f, Vec3(0.0, 0.5, 0.0))
        }
        if (fishHolder.enabled != fighting) fishHolder.enabled = fighting
        // The net: in the left hand (its handle runs out to where the hoop is), or on its rest.
        val nh = netHand
        val hoop = netHoop
        if (nh != null && hoop != null) {
            val (x, d, z) = Layout.netBasis(hoop - nh)
            net.setPos(nh)
            net.transform().quaternion = quatFromBasis(x, d, z)
            net.setScale((nh.distanceTo(hoop) / NET_MODEL_REACH).toFloat())
            if (st == Trip.State.NETTABLE && hoop.y < 0.03) rings.spawn(Vec3(hoop.x, 0.0, hoop.z), 1, 0.4, 0.9, 0.4)
        } else if (netWasHeld) restNet()
        netWasHeld = nh != null
    }

    private fun waitingBefore(s: Trip.State?) = s == Trip.State.WAIT || s == Trip.State.BITE
    private var netWasHeld = false
    private var lastDt = 0.016
    private var blend = 0.0
    private var lastHandGrip = REST_GRIP
    private var lastHandDir = Vec3(0.0, 0.53, -0.85)
    private var pend = Vec3.ZERO
    private var pendV = Vec3.ZERO
    private var pendOn = false

    /** The float hanging from the rod's tip on its line before a cast: a damped pendulum, so it swings as the rod moves. */
    private fun pendulum(tip: Vec3, dt: Double): Vec3 {
        if (!pendOn) { pend = tip + Vec3(0.0, -HANG, 0.0); pendV = Vec3.ZERO; pendOn = true }
        val n = 4
        val h = dt / n
        repeat(n) {
            pendV = (pendV + Vec3(0.0, -9.8 * h, 0.0)) * kotlin.math.exp(-1.4 * h)
            pend = pend + pendV * h
            val d = pend - tip
            val len = d.length()
            if (len > HANG) {
                val nrm = d * (1.0 / len)
                pend = tip + nrm * HANG
                val out = pendV.dot(nrm)
                if (out > 0) pendV = pendV - nrm * out
            }
        }
        return pend
    }

    /** Where the hooked fish is: under the water out on the line, rising as it tires; at the surface when it can be netted. */
    private fun fishPlace(t: Trip, st: Trip.State): Vec3 = when (st) {
        Trip.State.NETTABLE -> fishForNet(t) + Vec3(0.0, 0.02 + 0.03 * sin(clock * 11), 0.0)
        Trip.State.FIGHT -> {
            val a = Math.toRadians(t.castYaw + 14 * sin(clock * 0.9) + (if (t.running > 0) 10 * sin(clock * 3.0) else 0.0))
            val d = t.distance + 0.6
            Vec3(d * sin(a), -0.25 - 0.2 * (1 - t.tension), -d * cos(a))
        }
        else -> floatPoint(t)
    }

    /** The tired fish where the net should take it (its place without the splashing), for the hands. */
    fun fishNow(t: Trip): Vec3 = fishForNet(t)

    /** The float's landings so far (the hands put the rod back in its holder on the next one). */
    val landingCount get() = landings

    private fun sizeOf(t: Trip): Float {
        val f = t.fish ?: return 0.3f
        val base = when (f.id) { "bighead", "king" -> 0.7f; "carp", "silver" -> 0.55f; "culter", "bream", "mandarin", "koi" -> 0.4f; else -> 0.25f }
        return base * (0.7f + 0.6f * ((t.jin - f.minJin) / (f.maxJin - f.minJin + 1e-9)).toFloat()).coerceAtLeast(0.5f)
    }

    /** A fish landed: held up in front of the player for a few seconds, then into the basket. */
    fun landed(id: String, scale: Float) {
        shown.getChildren().forEach { it.destroy() }
        models[Fish.byId(id).model]?.clone(Entity.CloneOptions(true, true))?.let { m -> m.setScale(scale); shown.addChild(m) }
        shown.setPos(Vector3(0f, 1.05f, -0.55f))
        shown.enabled = true
        shownT = 0.0
        sparkle.emit(Vec3(0.0, 1.1, -0.55), 20, 0.4, 0.8, 0.008f)
        splash.emit(Vec3(0.0, 0.2, -1.5), 30, 1.0, 0.8, 0.02f, Vec3(0.0, 0.8, 0.0))
    }

    fun fishInBasket(n: Int) {
        while (basket.getChildren().size - 1 < minOf(n, 8)) {
            val k = basket.getChildren().size - 1
            basket.addChild(model(MeshResource.createCapsule(0.12f, 0.025f), pbr(color(0.7f, 0.72f, 0.75f), metallic = 0.3f, roughness = 0.3f)).apply {
                setPos(Vector3(-0.05f + (k % 4) * 0.03f, 0.1f + (k / 4) * 0.03f, 0f))
                transform().eulerAngles = EulerAngles(0f, k * 40f, 90f)
            })
        }
    }

    /** A cast was made: the float is in the air now; it lands (splash, rings) when its flight ends (see [frame]). */
    fun plop(t: Trip) {
        if (t.lake.ice) { val p = Vec3(0.2, 0.0, -0.75); splash.emit(p, 10, 0.4, 0.5, 0.012f, Vec3(0.0, 0.3, 0.0)); rings.spawn(p, 2, 0.4, 1.5) }
    }

    fun ripple(p: Vec3) = rings.spawn(p, 2, 0.8, 2.0)

    fun frame(dt: Double) {
        clock += dt
        lastDt = dt
        rings.frame(dt)
        scenery.frame(dt)
        if (flight >= 0) {
            flight += dt
            if (flight >= flightS) {
                flight = -1.0
                landings++
                splash.emit(flightTo, 12, 0.45, 0.6, 0.012f, Vec3(0.0, 0.35, 0.0))
                rings.spawn(flightTo, 3, 1.1, 2.4, 0.85)
            }
        }
        water?.transform()?.eulerAngles = EulerAngles(0f, (clock * 0.6).toFloat(), 0f)
        water2?.transform()?.eulerAngles = EulerAngles(0f, (-clock * 1.1).toFloat(), 0f)
        splash.update(dt, 0.2)
        sparkle.update(dt)
        val keep = ArrayList<Pair<Entity, Double>>()
        for ((e, age) in ripples) {
            val a = age + dt
            if (a > 2.0) e.destroy() else { e.setScale((1 + a * 3).toFloat()); keep += e to a }
        }
        ripples.clear(); ripples.addAll(keep)
        if (shownT >= 0) {
            shownT += dt
            shown.transform().eulerAngles = EulerAngles(0f, (shownT * 60).toFloat(), 0f)
            if (shownT > 4.0) { shown.enabled = false; shownT = -1.0 }
        }
        float.getChildren().firstOrNull()?.transform()?.eulerAngles = EulerAngles((4 * sin(clock * 1.7)).toFloat(), 0f, (4 * sin(clock * 1.3)).toFloat())
    }

    fun useIbl(t: TextureResource) {
        iblSource.components.set(ImageBasedLightComponent(ImageBasedLightSource.Single(t), 9f))
        receive(root)
    }

    private fun receive(e: Entity) {
        if (iblSource.components[ImageBasedLightComponent::class.java] == null) return
        if (e.components[ModelComponent::class.java] != null && e.components[ImageBasedLightReceiverComponent::class.java] == null) {
            e.components.set(ImageBasedLightReceiverComponent(iblSource))
            e.components.set(EnvironmentLightingSettingsComponent(0f))
        }
        e.getChildren().forEach(::receive)
    }

    companion object {
        const val ROD_LEN = 1.8f
        /** A cast's float in the air (s) when nothing else says (v0.4: from the throw, see [OverheadCast.flightSeconds]). */
        const val FLIGHT_S = 0.85
        /** The eye's height in the scene (the stage places the scene from the measured eyes: core.EyeHeight). */
        const val EYE = Layout.EYE
        /** The rod's grip in its holder, ready to cast (v0.5: core.Layout, 13 cm further out than 0.4). */
        val REST_GRIP = Layout.REST_GRIP
        const val PIVOT_BACK = Layout.PIVOT_BACK
        val PIVOT = Layout.PIVOT
        const val TAKE_S = 0.12
        const val PUT_BACK_S = 0.4
        /** The float hangs this far under the tip before a cast. */
        const val HANG = 0.55
        const val NET_MODEL_REACH = Layout.NET_MODEL_REACH
        const val SKY_R = 60f
        const val WATER_R = 22f

        fun setMaterials(e: Entity, m: Material) {
            e.components[ModelComponent::class.java]?.materials?.let { mats -> for (i in 0 until mats.size) mats[i] = m }
            e.getChildren().forEach { setMaterials(it, m) }
        }
    }
}
