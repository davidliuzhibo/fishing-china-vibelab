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
import com.vrgames.fishing.core.Fish
import com.vrgames.fishing.core.Lake
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
    private val sphere = MeshResource.createSphere(1f).shared()
    private val splash = Sparks("splash", 200, sphere, pbr(color(0.9f, 0.95f, 1f, 0.8f), roughness = 0.2f, blending = BlendingMode.TRANSPARENT).shared()).also { root.addChild(it.entity) }
    private val sparkle = Sparks("sparkle", 120, sphere, glow(color(1f, 0.85f, 0.4f, 1f)).shared()).also { root.addChild(it.entity) }
    private val iblSource = Entity().also { root.addChild(it) }
    private var sky: Entity? = null
    private var water: Entity? = null
    private val rod = Entity().also { root.addChild(it) }
    private val rodTip = Entity().also { rod.addChild(it) }
    private val line = model(MeshResource.createCylinder(1f, 0.0012f), unlit(color(0.95f, 0.95f, 0.9f, 0.8f), BlendingMode.TRANSPARENT).shared()).also { root.addChild(it) }
    private val float = Entity().also { root.addChild(it) }
    private val fishHolder = Entity().also { root.addChild(it) }
    private var fishModel: Entity? = null
    private var fishId: String? = null
    private val shown = Entity().also { root.addChild(it) }
    private var shownT = -1.0
    private val ripples = ArrayList<Pair<Entity, Double>>()
    private val marker = model(MeshResource.createTorus(0.25f, 0.02f), glow(color(1f, 0.85f, 0.35f, 0.7f)).shared()).also { it.enabled = false; root.addChild(it) }
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
     * The landing net, made here (an AI model cannot make a net's open mesh): a wooden handle, an
     * aluminium hoop, and a bag of green netting (a see-through diamond mesh, both sides drawn).
     */
    private fun landingNet() = Entity().apply {
        addChild(model(MeshResource.createCylinder(0.62f, 0.011f), pbr(color(0.45f, 0.3f, 0.18f), roughness = 0.6f)).apply { setPos(Vector3(0f, 0.31f, 0f)) })
        addChild(model(MeshResource.createCylinder(0.1f, 0.013f), pbr(color(0.1f, 0.1f, 0.1f), roughness = 0.9f)).apply { setPos(Vector3(0f, 0.08f, 0f)) })
        val top = Entity().apply { setPos(Vector3(0f, 0.62f + 0.17f, 0f)) }
        top.addChild(model(MeshResource.createTorus(0.17f, 0.158f), chrome))
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
        // The bag hangs below the hoop: a cone, point down.
        top.addChild(model(MeshResource.createCone(0.24f, 0.165f), mesh).apply { setPos(Vector3(0f, -0.12f, 0f)); transform().eulerAngles = EulerAngles(180f, 0f, 0f) })
        addChild(top)
    }

    init {
        // The rod in front, slightly right, pointing out over the water.
        rod.setPos(Vector3(0.22f, 0.95f, -0.3f))
        rod.transform().eulerAngles = EulerAngles(32f, 0f, 0f)
        val rm = models["rod"]?.clone(Entity.CloneOptions(true, true))
        if (rm != null) {
            rm.setScale(ROD_LEN)
            rm.transform().eulerAngles = EulerAngles(-90f, 0f, 0f)
            rod.addChild(rm)
        } else rod.addChild(model(MeshResource.createCylinder(ROD_LEN, 0.008f), rodMat).apply { setPos(Vector3(0f, 0f, -ROD_LEN / 2)); transform().eulerAngles = EulerAngles(90f, 0f, 0f) })
        rodTip.setPos(Vector3(0f, 0f, -ROD_LEN))
        // The reel: a crank to pinch and turn.
        val reel = Entity().apply { setPos(Vector3(0.0f, -0.05f, -0.12f)) }
        reel.addChild(model(MeshResource.createCylinder(0.03f, 0.03f), chrome).apply { transform().eulerAngles = EulerAngles(0f, 0f, 90f) })
        reel.addChild(model(MeshResource.createBox(Vector3(0.008f, 0.06f, 0.008f), 0.002f), chrome).apply { setPos(Vector3(0.022f, 0.025f, 0f)) })
        reel.addChild(model(sphere, rodMat).apply { setPos(Vector3(0.03f, 0.05f, 0f)); setScale(0.012f) })
        hit(reel, "reel", Vector3(0.12f, 0.12f, 0.12f))
        rod.addChild(reel)
        // The float.
        float.addChild(model(MeshResource.createCapsule(0.06f, 0.012f), red).apply { setPos(Vector3(0f, 0.02f, 0f)) })
        float.addChild(model(MeshResource.createCapsule(0.03f, 0.013f), white).apply { setPos(Vector3(0f, -0.01f, 0f)) })
        float.enabled = false
        // The bait box at the left, the net at the right.
        val bait = Entity().apply { setPos(Vector3(-0.42f, 0.62f, -0.5f)) }
        (models["baitbox"]?.clone(Entity.CloneOptions(true, true))?.also { it.setScale(0.12f) } ?: model(MeshResource.createBox(Vector3(0.18f, 0.06f, 0.12f), 0.01f), wood)).let { bait.addChild(it) }
        hit(bait, "bait", Vector3(0.2f, 0.12f, 0.16f))
        root.addChild(bait)
        val net = Entity().apply { setPos(Vector3(0.55f, 0.62f, -0.5f)); transform().eulerAngles = EulerAngles(0f, 0f, -60f) }
        net.addChild(landingNet())
        hit(net, "net", Vector3(0.25f, 0.5f, 0.25f))
        root.addChild(net)
        // Everything at the side stays half a metre out, clear of the seated body.
        basket.setPos(Vector3(-0.62f, 0.45f, -0.4f))
        basket.addChild(model(MeshResource.createCylinder(0.16f, 0.12f), pbr(color(0.62f, 0.5f, 0.3f), roughness = 0.9f)))
        fishHolder.enabled = false
        shown.enabled = false
    }

    private fun hit(e: Entity, id: String, size: Vector3) {
        e.components.set(CollisionComponent(listOf(ShapeResource.createBox(size)), PhysicsMaterialResource()))
        e.components.set(InteractableComponent())
        e.components.set(HoverEffectComponent())
        hits[e] = id
    }

    /** The invisible wall ahead that takes pinches over the water. */
    fun registerWater(e: Entity) {
        hits[e] = "water"
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
            if (t != null) { setBaseColorTexture(t); setEmissiveTexture(t); setEmissiveColor(color(0.2f, 0.22f, 0.24f)) }
            setBaseColor(color(0.35f, 0.5f, 0.55f, 0.82f))
            setRoughness(0.08f)
            setMetallic(0.2f)
        }
        water = model(MeshResource.createCylinder(0.02f, WATER_R), wm).also { it.setPos(Vector3(0f, -0.01f, 0f)); root.addChild(it) }
        when (l.id) {
            "xihu", "night" -> {
                // A stone embankment under the seat.
                placeRoot.addChild(model(MeshResource.createBox(Vector3(3f, 0.5f, 1.6f), 0.02f), stone).apply { setPos(Vector3(0f, 0.1f, 0.6f)) })
                for (k in 0 until 6) placeRoot.addChild(model(MeshResource.createBox(Vector3(0.5f, 0.06f, 0.3f), 0.01f), stone).apply { setPos(Vector3(-1.25f + k * 0.5f, 0.38f, -0.15f)) })
            }
            "qiandao" -> models["boat"]?.clone(Entity.CloneOptions(true, true))?.let { b -> b.setScale(1.4f); b.setPos(Vector3(0f, -0.15f, 0.3f)); b.transform().eulerAngles = EulerAngles(0f, 90f, 0f); placeRoot.addChild(b) }
                ?: placeRoot.addChild(model(MeshResource.createBox(Vector3(1.2f, 0.3f, 3f), 0.1f), wood).apply { setPos(Vector3(0f, 0.05f, 0.4f)) })
            "lijiang" -> {
                models["raft"]?.clone(Entity.CloneOptions(true, true))?.let { b -> b.setScale(0.9f); b.setPos(Vector3(0f, -0.05f, 0.3f)); b.transform().eulerAngles = EulerAngles(0f, 90f, 0f); placeRoot.addChild(b) }
                    ?: placeRoot.addChild(model(MeshResource.createBox(Vector3(1.2f, 0.12f, 3f), 0.03f), pbr(color(0.5f, 0.6f, 0.3f), roughness = 0.7f)).apply { setPos(Vector3(0f, 0.03f, 0.4f)) })
                for ((yaw, dist) in l.rocksAt) {
                    val a = Math.toRadians(yaw)
                    placeRoot.addChild(model(sphere, stone).apply {
                        setPos(Vector3((dist * sin(a)).toFloat(), 0.3f, (-dist * cos(a)).toFloat()))
                        transform().setScaleVector(Vector3(1.4f, 0.8f, 1.1f))
                    })
                }
            }
            else -> {
                // The ice hole in front.
                placeRoot.addChild(model(MeshResource.createCylinder(0.03f, 0.22f), dark).apply { setPos(Vector3(0.2f, 0.0f, -0.75f)) })
                placeRoot.addChild(model(MeshResource.createTorus(0.23f, 0.03f), iceMat).apply { setPos(Vector3(0.2f, 0.01f, -0.75f)) })
            }
        }
        receive(root)
    }

    // ---- Each frame ----

    /** The float, line, rod bend, the fighting fish, the aim marker, from the trip's state. */
    fun sync(t: Trip, aimYaw: Double) {
        val tip = rodTip.convertPositionTo(Vector3(0f, 0f, 0f), root)
        val targetBend = when (t.state) { Trip.State.FIGHT, Trip.State.NETTABLE -> (t.tension * 28).toFloat(); Trip.State.BITE -> 10f; else -> 0f }
        bend += (targetBend - bend) * 0.2f
        rod.transform().eulerAngles = EulerAngles(32f - bend, 0f, 0f)
        // Where the float is.
        val out = t.state != Trip.State.READY && t.state != Trip.State.POWER && t.state != Trip.State.LANDED
        if (float.enabled != out) float.enabled = out
        val dist = if (t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE) t.distance else if (t.lake.ice) 0.0 else t.castDist
        val a = Math.toRadians(t.castYaw)
        val fp = if (t.lake.ice) Vec3(0.2, 0.0, -0.75) else Vec3(dist * sin(a), 0.0, -dist * cos(a) - 0.6)
        val dip = when {
            t.state == Trip.State.BITE -> -0.08
            t.nibbleT > 0 -> -0.025 * sin(t.nibbleT * 25)
            t.state == Trip.State.FIGHT -> -0.05
            else -> 0.008 * sin(clock * 2.2)
        }
        float.setPos(Vector3(fp.x.toFloat(), dip.toFloat(), fp.z.toFloat()))
        // The line from the rod tip to the float.
        val lineOn = out
        if (line.enabled != lineOn) line.enabled = lineOn
        if (lineOn) stretch(line, Vec3(tip.x.toDouble(), tip.y.toDouble(), tip.z.toDouble()), fp + Vec3(0.0, 0.03 + dip, 0.0))
        // The fish in the fight: under the float, rising as it tires; out of the water when nettable.
        val fighting = t.state == Trip.State.FIGHT || t.state == Trip.State.NETTABLE
        if (fighting) {
            val f = t.fish
            if (f != null && fishId != f.id) {
                fishModel?.destroy()
                fishModel = models[f.model]?.clone(Entity.CloneOptions(true, true))?.also { m -> m.setScale(sizeOf(t)); fishHolder.addChild(m) }
                fishId = f.id
            }
            val depth = if (t.state == Trip.State.NETTABLE) 0.02 else -0.25 - 0.2 * (1 - t.tension)
            fishHolder.setPos(Vector3(fp.x.toFloat(), depth.toFloat(), fp.z.toFloat()))
            fishHolder.transform().eulerAngles = EulerAngles((10 * sin(clock * 9)).toFloat(), Math.toDegrees(atan2(fp.x, fp.z)).toFloat() + (25 * sin(clock * 6)).toFloat(), 0f)
            if (t.running > 0 && (clock * 10).toInt() % 3 == 0) splash.emit(fp, 4, 0.6, 0.6, 0.02f, Vec3(0.0, 0.5, 0.0))
        }
        if (fishHolder.enabled != fighting) fishHolder.enabled = fighting
        // The aim marker while the power swings: where the cast would land.
        val aiming = t.state == Trip.State.POWER
        if (marker.enabled != aiming) marker.enabled = aiming
        if (aiming) {
            val d = 3.0 + 13.0 * t.power
            val ay = Math.toRadians(aimYaw)
            marker.setPos(Vector3((d * sin(ay)).toFloat(), 0.02f, (-d * cos(ay) - 0.6).toFloat()))
            marker.setScale((0.6 + 0.04 * d).toFloat())
        }
    }

    private fun sizeOf(t: Trip): Float {
        val f = t.fish ?: return 0.3f
        val base = when (f.id) { "bighead", "king" -> 0.7f; "carp", "silver" -> 0.55f; "culter", "bream", "mandarin", "koi" -> 0.4f; else -> 0.25f }
        return base * (0.7f + 0.6f * ((t.jin - f.minJin) / (f.maxJin - f.minJin + 1e-9)).toFloat()).coerceAtLeast(0.5f)
    }

    private fun stretch(e: Entity, a: Vec3, b: Vec3) {
        val d = b - a
        val len = d.length().toFloat()
        e.setPos(Vector3(((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat(), ((a.z + b.z) / 2).toFloat()))
        val pitch = Math.toDegrees(atan2(hypot(d.x, d.z), d.y)).toFloat()
        val yaw = Math.toDegrees(atan2(d.x, d.z)).toFloat()
        e.transform().eulerAngles = EulerAngles(pitch, yaw, 0f)
        e.transform().setScaleVector(Vector3(1f, len, 1f))
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

    fun plop(t: Trip) {
        val a = Math.toRadians(t.castYaw)
        val p = if (t.lake.ice) Vec3(0.2, 0.0, -0.75) else Vec3(t.castDist * sin(a), 0.0, -t.castDist * cos(a) - 0.6)
        splash.emit(p, 10, 0.4, 0.5, 0.012f, Vec3(0.0, 0.3, 0.0))
        ripple(p)
    }

    fun ripple(p: Vec3) {
        val r = model(MeshResource.createTorus(0.1f, 0.006f), unlit(color(1f, 1f, 1f, 0.5f), BlendingMode.TRANSPARENT)).apply { setPos(Vector3(p.x.toFloat(), 0.01f, p.z.toFloat())) }
        root.addChild(r)
        ripples += r to 0.0
    }

    fun frame(dt: Double) {
        clock += dt
        water?.transform()?.eulerAngles = EulerAngles(0f, (clock * 0.6).toFloat(), 0f)
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
        const val SKY_R = 60f
        const val WATER_R = 22f

        fun setMaterials(e: Entity, m: Material) {
            e.components[ModelComponent::class.java]?.materials?.let { mats -> for (i in 0 until mats.size) mats[i] = m }
            e.getChildren().forEach { setMaterials(it, m) }
        }
    }
}
