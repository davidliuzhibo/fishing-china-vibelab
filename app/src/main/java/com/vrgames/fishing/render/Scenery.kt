package com.vrgames.fishing.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshInstancesResource
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Transform
import com.pico.spatial.core.math.Vector3
import com.vrgames.fishing.core.Lake
import com.vrgames.fishing.core.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.6.0 (PICO 1125: 「需要对场景的美术上做大调整，现在很简单」): each place in three layers around the seat.
 *
 * - Near (the bank, the boat, the raft, the ice by the hole): lotus and reeds at the water's edge, a stone bench, the ice
 *   camp with its sled, fishermen and the morning's catch.
 * - Middle (8–40 m, real 3D): 三潭印月's stone lanterns, the broken bridge, a painted pleasure boat drifting by; green
 *   islands and a fish farm; an old fisherman's raft on the Li river (its peaks are the panorama's); a birch shore.
 * - Far: the panorama (60 m out), its horizon met by a wide outer water (or ice) ring so the islands and peaks stand
 *   in water rather than over the picture.
 * And things that move: egrets wheeling overhead, a fish leaping now and then (rings and a splash), snow over the ice.
 *
 * Everything sits out of the cast's lane (ahead, ±35°, 5–18 m) and clear of the seat; models are loaded by the stage
 * (docs/素材来源.md); a missing one is skipped. The frame: the player's feet at the origin facing -z, water at y = 0.
 */
class Scenery(
    private val parent: Entity,
    private val models: Map<String, Entity>,
    private val tex: Map<String, TextureResource>,
    private val splash: (Vec3) -> Unit,
    private val ring: (Vec3, Double) -> Unit,
) {
    private var root = Entity().also { parent.addChild(it) }
    private val movers = ArrayList<Mover>()
    private var jumper: Entity? = null
    private var jumpT = -1.0
    private var nextJump = 6.0
    private var jumpFrom = Vec3.ZERO
    private var jumpYaw = 0f
    private var snow: Snow? = null
    private var clock = 0.0
    private var seed = 7
    private var lakeId = ""

    /** Something that moves on its own: [f] puts it at time t. */
    private class Mover(val e: Entity, val f: (Entity, Double) -> Unit)

    fun build(l: Lake) {
        root.destroy()
        root = Entity().also { parent.addChild(it) }
        movers.clear()
        jumper = null
        jumpT = -1.0
        snow = null
        lakeId = l.id
        val night = l.id == "night"
        // The outer ring: water (or ice) out to the panorama, under the near water's disc. Unlit, in the near water's own
        // texture and tone (lit, it mirrored the bright sky: a white band round the lake on the emulator, 2026-11-25).
        val ringMat = UnlitMaterial.create().apply {
            if (!l.ice) tex["water"]?.let { setBaseColorTexture(it) }
            setBaseColor(when {
                l.ice -> color(0.84f, 0.88f, 0.92f)
                night -> color(0.10f, 0.13f, 0.17f)
                l.id == "lijiang" -> color(0.62f, 0.66f, 0.62f)
                else -> color(0.66f, 0.78f, 0.80f)
            })
        }
        root.addChild(model(MeshResource.createCylinder(0.01f, 58f), ringMat).apply { setPos(Vector3(0f, -0.03f, 0f)) })
        when (l.id) {
            "xihu", "night" -> xihu(night)
            "qiandao" -> qiandao()
            "lijiang" -> lijiang()
            else -> chagan()
        }
        // A fish that leaps now and then (not on the ice).
        if (!l.ice) {
            val kind = when (l.id) { "lijiang" -> "culter"; "qiandao" -> "silver"; "night" -> "koi"; else -> "crucian" }
            jumper = models[kind]?.clone(Entity.CloneOptions(true, true))?.also { it.setScale(0.32f); it.enabled = false; root.addChild(it) }
            nextJump = 5.0 + 6 * rnd()
        }
    }

    // ---- The places ----

    private fun xihu(night: Boolean) {
        // Near: lotus by the bank either side, reeds at the bank's ends, a stone bench behind at the left.
        prop("lotus", 1.7f, -2.4f, -0.02f, -2.2f, 10f)
        prop("lotus", 2.2f, -4.0f, -0.02f, -3.6f, 70f)
        prop("lotus", 1.5f, 2.9f, -0.02f, -2.4f, -30f)
        prop("lotus", 2.0f, 4.6f, -0.02f, -1.2f, 140f)
        prop("lotus", 2.4f, -6.5f, -0.02f, -6.0f, 200f)
        prop("reeds", 1.5f, -1.55f, 0.0f, -0.45f, 0f)
        prop("reeds", 1.3f, 1.65f, 0.0f, -0.35f, 90f)
        prop("stonebench", 1.4f, -0.9f, 0.35f, 1.0f, 90f)
        // Middle: 三潭印月 out on the lake at the left, the broken bridge at the right (雷峰塔 is in the panorama).
        prop("santan", 5.5f, -12f, -0.6f, -34f, 15f)
        prop("bridge", 18f, 24f, -0.2f, -40f, 60f)
        // A painted pleasure boat crossing slowly far out (about 3 minutes from side to side), lanterns lit at night.
        prop("huafang", 9f, 0f, -0.15f, -30f, 0f)?.let { b ->
            if (night) b.addChild(model(MeshResource.createSphere(0.06f), glow(color(1f, 0.55f, 0.25f, 0.9f))).apply { setPos(Vector3(0f, 0.4f, 0f)) })
            movers += Mover(b) { e, t ->
                val u = ((t / 190.0) % 1.0)
                e.setPos(Vector3((-38 + 76 * u).toFloat(), -0.15f, (-30 - 4 * sin(u * PI)).toFloat()))
            }
        }
        if (!night) egrets(2, 9.0)
    }

    private fun qiandao() {
        // Green islands all round the boat, sunk to their wooded slopes (their bases are cut flat under the water).
        island("island", 20f, -24f, -40f, 20f)
        island("island2", 26f, 30f, -38f, -35f)
        island("island", 16f, 46f, 8f, 80f)
        island("island2", 22f, -44f, 12f, 120f)
        island("island", 14f, 4f, 48f, 200f)
        island("island2", 12f, -10f, -54f, 160f)
        // A fish farm off to the left, a little way out.
        prop("fishfarm", 7f, -11f, -1.15f, -13f, 25f)
        egrets(3, 11.0)
    }

    private fun lijiang() {
        // The panorama is a whole ring of karst peaks and bamboo banks already; 3D peaks, mist and banks in front of it
        // looked like pale toy cones (emulator, 2026-11-25) and were taken out. What moves on the river is added.
        // An old fisherman's raft drifting down past the left, slowly (about 2½ minutes), then round again.
        prop("fisherman", 4.2f, -7f, -0.05f, -20f, 90f)?.let { b ->
            movers += Mover(b) { e, t ->
                val u = ((t / 150.0) % 1.0)
                e.setPos(Vector3((-9 + 2 * sin(u * 2 * PI)).toFloat(), -0.05f, (-34 + 26 * u).toFloat()))
            }
        }
        egrets(2, 8.0)
    }

    private fun chagan() {
        // The far shore: a birch wood in an arc behind and to the sides.
        for (k in 0 until 12) {
            val a = Math.toRadians(70.0 + 220.0 * k / 11)
            val d = 40.0 + 9 * ((k * 37) % 7) / 7.0
            prop("birch", (8f + (k % 3) * 1.5f), (d * sin(a)).toFloat(), -0.1f, (-d * cos(a)).toFloat(), 33f * k)
        }
        // The winter catch out on the ice: a big hole in the ice, a red flag, fishermen hauling the net, the fish piled up,
        // a horse with its sled waiting.
        root.addChild(model(MeshResource.createCylinder(0.02f, 1f), pbr(color(0.02f, 0.05f, 0.07f), roughness = 0.1f)).apply {
            setPos(Vector3(10f, 0.005f, -16f)); transform().setScaleVector(Vector3(2.2f, 1f, 0.9f))
        })
        root.addChild(model(MeshResource.createCylinder(3.2f, 0.03f), pbr(color(0.5f, 0.4f, 0.3f))).apply { setPos(Vector3(7.6f, 1.6f, -15.2f)) })
        root.addChild(model(MeshResource.createBox(Vector3(0.9f, 0.55f, 0.01f), 0f), pbr(color(0.85f, 0.08f, 0.06f), roughness = 0.8f)).apply { setPos(Vector3(8.06f, 2.9f, -15.2f)) })
        prop("winterfisher", 2.45f, 8.2f, 0f, -14.6f, -70f)
        prop("winterfisher", 2.45f, 11.8f, 0f, -15.2f, 110f)
        prop("winterfisher", 2.45f, 10.4f, 0f, -18.0f, 170f)
        prop("fishpile", 1.6f, 6.8f, 0f, -12.4f, 20f)
        prop("sled", 4.2f, -7f, 0f, -11f, 65f)
        // Snowdrifts here and there on the ice.
        val drift = pbr(color(0.95f, 0.97f, 1f), roughness = 0.9f)
        for (k in 0 until 9) {
            val a = Math.toRadians(-140.0 + 280.0 * k / 8)
            val d = 6.0 + 3.5 * (k % 3)
            root.addChild(model(MeshResource.createSphere(1f), drift).apply {
                setPos(Vector3((d * sin(a)).toFloat(), -0.12f, (-d * cos(a)).toFloat()))
                transform().setScaleVector(Vector3(1.2f + 0.3f * (k % 2), 0.22f, 0.7f))
                transform().eulerAngles = EulerAngles(0f, 41f * k, 0f)
            })
        }
        snow = Snow(root)
    }

    // ---- Helpers ----

    /** Model [name] at [size] m (its longest side), at x, y, z turned [yaw]°; null when it did not load. */
    private fun prop(name: String, size: Float, x: Float, y: Float, z: Float, yaw: Float): Entity? {
        val m = models[name]?.clone(Entity.CloneOptions(true, true)) ?: return null
        m.setScale(size)
        m.setPos(Vector3(x, y, z))
        m.transform().eulerAngles = EulerAngles(0f, yaw, 0f)
        root.addChild(m)
        return m
    }

    /** An island sunk so its flat-cut base and soil sides are under the water ("island" stands on a soil block). */
    private fun island(name: String, size: Float, x: Float, z: Float, yaw: Float) =
        prop(name, size, x, -(if (name == "island") 0.16f else 0.035f) * size, z, yaw)?.also { haze(it, 0.32f, color(0.74f, 0.83f, 0.9f)) }

    private val hazed = HashSet<Any>()

    /**
     * Distance haze (no fog in the engine): the model's materials darkened by [a] and lit by [c] × [a] as emission, so a
     * far island or peak takes the air's colour instead of standing out saturated and sharp like a toy (emulator,
     * 2026-11-25). The materials are the template's own (every copy alike); each is hazed once.
     */
    private fun haze(e: Entity, a: Float, c: com.pico.spatial.core.math.Color4) {
        e.components[ModelComponent::class.java]?.materials?.let { mats ->
            for (i in 0 until mats.size) {
                val m = mats[i] as? com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial ?: continue
                if (!hazed.add(m)) continue
                m.setBaseColor(color(1f - a, 1f - a, 1f - a, 1f))
                m.setEmissiveColor(color(c.red * a, c.green * a, c.blue * a, 1f))
            }
        }
        e.getChildren().forEach { haze(it, a, c) }
    }

    /** [n] egrets wheeling round at about [height] m, each on its own circle. */
    private fun egrets(n: Int, height: Double) {
        for (k in 0 until n) {
            val b = prop("egret", 1.5f, 0f, height.toFloat(), -20f, 0f) ?: return
            val r = 16.0 + 7 * k
            val speed = 0.11 + 0.025 * k
            val phase = 2.1 * k
            val cx = -6.0 + 9 * k; val cz = -22.0 + 4 * k
            movers += Mover(b) { e, t ->
                val a = phase + t * speed
                val x = cx + r * cos(a); val z = cz + r * sin(a)
                e.setPos(Vector3(x.toFloat(), (height + 0.8 * k + 0.6 * sin(t * 0.7 + k)).toFloat(), z.toFloat()))
                // Flying along the circle (its tangent (-sin a, cos a); the model faces +z), banked into the turn.
                e.transform().eulerAngles = EulerAngles(0f, Math.toDegrees(-a).toFloat(), 12f)
            }
        }
    }

    private fun rnd(): Double {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65536.0
    }

    // ---- Each frame ----

    fun frame(dt: Double) {
        clock += dt
        for (m in movers) m.f(m.e, clock)
        snow?.frame(dt)
        val j = jumper ?: return
        if (jumpT < 0) {
            nextJump -= dt
            if (nextJump <= 0) {
                // Somewhere to the side or far out, never in the cast's lane (ahead, ±35°, under 20 m).
                val side = if (rnd() < 0.5) -1 else 1
                val yaw = side * (40 + 50 * rnd())
                val d = 7 + 13 * rnd()
                val a = Math.toRadians(yaw)
                jumpFrom = Vec3(d * sin(a), 0.0, -d * cos(a))
                jumpYaw = (90 * rnd() - 45).toFloat() + (if (side > 0) 90f else -90f)
                jumpT = 0.0
                j.enabled = true
                splash(jumpFrom); ring(jumpFrom, 0.7)
            }
            return
        }
        jumpT += dt
        val u = (jumpT / JUMP_S).coerceAtMost(1.0)
        val f = Vec3(sin(Math.toRadians(jumpYaw.toDouble())), 0.0, -cos(Math.toRadians(jumpYaw.toDouble())))
        val p = jumpFrom + f * (0.9 * u) + Vec3(0.0, 0.55 * 4 * u * (1 - u) - 0.05, 0.0)
        j.setPos(p)
        j.transform().eulerAngles = EulerAngles((50 - 100 * u).toFloat(), jumpYaw, 0f)
        if (u >= 1.0) {
            j.enabled = false
            jumpT = -1.0
            nextJump = 7.0 + 9 * rnd()
            val end = Vec3(p.x, 0.0, p.z)
            splash(end); ring(end, 0.9)
        }
    }

    /** Snow over the ice: flakes drifting down in a box round the seat, back to the top when they reach the ice. */
    private class Snow(root: Entity) {
        private val n = 260
        private val inst = MeshInstancesResource.create("snow", (0 until n).map { MeshInstancesResource.Instance("snow$it", HIDDEN) })
        private val e: Entity = ModelEntity(MeshResource.createSphere(1f), unlit(color(1f, 1f, 1f, 1f)))
        private val x = DoubleArray(n); private val y = DoubleArray(n); private val z = DoubleArray(n); private val ph = DoubleArray(n)
        private var t = 0.0
        private var s = 3

        init {
            e.components[ModelComponent::class.java]!!.meshInstances = inst
            root.addChild(e)
            for (i in 0 until n) { x[i] = r() * 16 - 8; y[i] = r() * 6; z[i] = r() * 16 - 10; ph[i] = r() * 6.3 }
        }

        private fun r(): Double { s = s * 1103515245 + 12345; return ((s ushr 8) and 0xFFFF) / 65536.0 }

        fun frame(dt: Double) {
            t += dt
            for (i in 0 until n) {
                y[i] -= dt * (0.45 + 0.25 * ((i % 5) / 4.0))
                if (y[i] < 0) { y[i] += 6.0; x[i] = r() * 16 - 8; z[i] = r() * 16 - 10 }
                val sway = 0.25 * sin(t * 0.8 + ph[i])
                val size = (0.008 + 0.006 * (i % 3)).toFloat()
                inst.update("snow$i", Transform(Vector3((x[i] + sway).toFloat(), y[i].toFloat(), z[i].toFloat()), NO_ROT, Vector3(size)))
            }
        }

        companion object {
            val NO_ROT = EulerAngles(0f, 0f, 0f)
            val HIDDEN = Transform(Vector3(0f, -100f, 0f), NO_ROT, Vector3(0f))
        }
    }

    companion object {
        /** A leaping fish's time out of the water (s). */
        const val JUMP_S = 0.7
        /** The models this adds to the stage's list. */
        val MODELS = listOf("lotus", "reeds", "stonebench", "santan", "bridge", "huafang", "island", "island2", "fishfarm",
            "fisherman", "birch", "winterfisher", "fishpile", "sled", "egret")
    }
}
