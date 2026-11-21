package com.vrgames.fishing.render

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.vrgames.fishing.core.Layout
import com.vrgames.fishing.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.3 (PICO 1117/1118: the rod was a prop; 「收线的时候有动画」): the tackle as a real thing. A carbon rod that bends
 * toward the line as it is loaded (and whips as it is cast), with its cork grip, reel seat and guides; a spinning reel
 * whose rotor and handle turn as it is wound; a line that sags when slack and draws straight when tight; a landing net
 * whose handle runs out when it is taken up. Built here from simple shapes so every part can move.
 */
class Tackle(private val root: Entity) {
    private val unitCyl = MeshResource.createCylinder(1f, 1f).shared()
    private val sphere = MeshResource.createSphere(1f).shared()
    private val carbon = pbr(color(0.07f, 0.08f, 0.1f), metallic = 0.35f, roughness = 0.22f).shared()
    private val wrap = pbr(color(0.62f, 0.12f, 0.08f), metallic = 0.2f, roughness = 0.35f).shared()
    private val cork = pbr(color(0.72f, 0.55f, 0.36f), roughness = 0.85f).shared()
    private val chrome = pbr(color(0.82f, 0.84f, 0.88f), metallic = 0.95f, roughness = 0.18f).shared()
    private val gunmetal = pbr(color(0.18f, 0.2f, 0.23f), metallic = 0.85f, roughness = 0.3f).shared()
    private val gold = pbr(color(0.85f, 0.65f, 0.25f), metallic = 0.9f, roughness = 0.25f).shared()
    // v0.4 (device test 2026-11-20: the reel was dark and its knob hard to find): a lighter reel body, a bright knob.
    private val reelBody = pbr(color(0.78f, 0.8f, 0.84f), metallic = 0.7f, roughness = 0.28f).shared()
    private val knobMat = pbr(color(1f, 0.58f, 0.12f), metallic = 0.2f, roughness = 0.35f, emissive = color(0.95f, 0.45f, 0.08f)).shared()
    private val knobGlowMat = rim(color(1f, 0.6f, 0.15f, 0.9f)).shared()
    private val gripGlowMat = rim(color(1f, 0.85f, 0.35f, 0.9f)).shared()
    val lineMat: UnlitMaterial = unlit(color(0.96f, 0.96f, 0.9f, 0.9f), BlendingMode.TRANSPARENT).shared()

    // ---- Rod ----
    private val handle = Entity().also { root.addChild(it) }
    private val rotor = Entity()
    private val blank = List(BLANK_SEGS) { i -> model(unitCyl, if (i == 0) wrap else carbon).also { root.addChild(it) } }
    private val guides = List(GUIDES.size) { model(MeshResource.createTorus(1f, 0.82f), chrome).also { root.addChild(it) } }
    private val tipTop = model(sphere, chrome).also { root.addChild(it) }
    private val nodes = Array(BLANK_SEGS + 1) { Vec3.ZERO }
    var tip = Vec3.ZERO; private set
    var dir = Vec3(0.0, 0.5, -0.86); private set
    var grip = Vec3.ZERO; private set
    private var right = Vec3(1.0, 0.0, 0.0)
    private var under = Vec3(0.0, -1.0, 0.0)

    private val crank = Entity()
    /** The handle (cork, seat, reel) and the reel: the gaze-and-pinch colliders hang on them (v0.5). */
    val handleNode: Entity get() = handle
    lateinit var reelNode: Entity; private set
    private var spin = 0.0
    private var rotorSpin = 0.0
    /** v0.4: the glow round the cork grip (take the rod here) and round the reel's knob (wind here). */
    private val gripGlow = model(unitCyl, gripGlowMat).apply { enabled = false }
    private val knobGlow = model(unitCyl, knobGlowMat).apply { enabled = false }

    // ---- Line ----
    private val line = List(LINE_SEGS) { model(unitCyl, lineMat).also { root.addChild(it); it.enabled = false } }

    init {
        // Grip, butt cap, reel seat, the winding check: along the rod's own +y, the hand at 0. v0.5 (device test
        // 2026-11-20: the grip sat at the chest): the butt reaches 12 cm behind the hand (was 20).
        handle.addChild(model(unitCyl, cork).apply { setPos(Vector3(0f, CORK_MID, 0f)); transform().setScaleVector(Vector3(0.0125f, CORK_LEN, 0.0125f)) })
        handle.addChild(model(sphere, gunmetal).apply { setPos(Vector3(0f, -Layout.BUTT.toFloat(), 0f)); transform().setScaleVector(Vector3(0.014f, 0.01f, 0.014f)) })
        handle.addChild(model(unitCyl, gunmetal).apply { setPos(Vector3(0f, 0.11f, 0f)); transform().setScaleVector(Vector3(0.011f, 0.09f, 0.011f)) })
        handle.addChild(model(unitCyl, cork).apply { setPos(Vector3(0f, 0.2f, 0f)); transform().setScaleVector(Vector3(0.0105f, 0.09f, 0.0105f)) })
        handle.addChild(model(unitCyl, gold).apply { setPos(Vector3(0f, 0.25f, 0f)); transform().setScaleVector(Vector3(0.0085f, 0.012f, 0.0085f)) })
        // The spinning reel hangs under the seat on its foot: the body, the spool along the rod, the rotor, the handle.
        val reel = Entity().apply { setPos(Vector3(0f, 0.11f, -0.075f)) }
        reel.addChild(model(unitCyl, gunmetal).apply { setPos(Vector3(0f, 0f, 0.045f)); transform().setScaleVector(Vector3(0.005f, 0.05f, 0.005f)); transform().eulerAngles = EulerAngles(90f, 0f, 0f) })
        reel.addChild(model(sphere, reelBody).apply { transform().setScaleVector(Vector3(0.028f, 0.034f, 0.03f)) })
        reel.addChild(model(unitCyl, chrome).apply { setPos(Vector3(0f, 0.042f, 0f)); transform().setScaleVector(Vector3(0.024f, 0.028f, 0.024f)) })
        reel.addChild(model(unitCyl, wrap).apply { setPos(Vector3(0f, 0.042f, 0f)); transform().setScaleVector(Vector3(0.0215f, 0.02f, 0.0215f)) })
        // The rotor turns about the rod's axis with its bail; the handle turns across it.
        rotor.setPos(Vector3(0f, 0.022f, 0f))
        rotor.addChild(model(unitCyl, gunmetal).apply { transform().setScaleVector(Vector3(0.026f, 0.014f, 0.026f)) })
        rotor.addChild(model(MeshResource.createTorus(1f, 0.93f), chrome).apply { setPos(Vector3(0f, 0.045f, 0f)); transform().setScaleVector(Vector3(0.03f)); transform().eulerAngles = EulerAngles(0f, 0f, 90f) })
        reel.addChild(rotor)
        // v0.4 (device test 2026-11-20: 「收线做不了」): the crank on the LEFT of the reel (the left hand's side, as on a
        // real spinning reel held in the right hand), out in the open: a 7 cm arm, a big bright orange knob.
        crank.setPos(Vector3(CRANK_X, -0.005f, 0f))
        crank.addChild(model(unitCyl, chrome).apply { setPos(Vector3(0f, 0f, -CRANK_ARM / 2)); transform().setScaleVector(Vector3(0.0035f, CRANK_ARM, 0.0035f)); transform().eulerAngles = EulerAngles(90f, 0f, 0f) })
        val knob = Entity().apply { setPos(Vector3(-0.022f, 0f, -CRANK_ARM)); transform().eulerAngles = EulerAngles(0f, 0f, 90f) }
        knob.addChild(model(unitCyl, knobMat).apply { transform().setScaleVector(Vector3(0.014f, 0.035f, 0.014f)) })
        knob.addChild(knobGlow.apply { transform().setScaleVector(Vector3(0.02f, 0.042f, 0.02f)) })
        crank.addChild(knob)
        reel.addChild(crank)
        handle.addChild(reel)
        handle.addChild(gripGlow.apply { setPos(Vector3(0f, CORK_MID, 0f)); transform().setScaleVector(Vector3(0.02f, CORK_LEN + 0.01f, 0.02f)) })
        reelNode = reel
        tipTop.setScale(0.0035f)
    }

    /** The reel turned (radians of the handle): the handle goes round, the rotor faster (a 5.2:1 gear). */
    fun turnReel(radians: Double) {
        spin += radians
        rotorSpin += kotlin.math.abs(radians)
        crank.transform().eulerAngles = EulerAngles(Math.toDegrees(spin).toFloat() % 360f, 0f, 0f)
        rotor.transform().eulerAngles = EulerAngles(0f, (Math.toDegrees(rotorSpin * 5.2) % 360).toFloat(), 0f)
    }

    /** v0.4: the crank set to where the winding hand is ([radians] from its rest direction, see [crankZero]). */
    fun setCrank(radians: Double, wound: Double) {
        spin = radians
        rotorSpin += wound
        crank.transform().eulerAngles = EulerAngles(Math.toDegrees(spin).toFloat() % 360f, 0f, 0f)
        rotor.transform().eulerAngles = EulerAngles(0f, (Math.toDegrees(rotorSpin * 5.2) % 360).toFloat(), 0f)
    }

    /** Where the reel's crank turns about (its hub), its axle, and the arm's direction at angle 0, for the winding hand. */
    fun reelCentre(): Vec3 = grip + dir * 0.105 + under * 0.075 + right * CRANK_X.toDouble()
    fun reelAxle(): Vec3 = right
    fun crankZero(): Vec3 = under
    /** Where the knob is now. */
    fun knobAt(): Vec3 = reelCentre() + (under * cos(spin) + dir * sin(spin)) * CRANK_ARM.toDouble() + right * -0.022

    /**
     * v0.4: the breathing glows. [grip] 0 off, else how bright (1 normal, 2 a hand near); [knob] the same for the reel's
     * knob; [phase] the breathing clock (s).
     */
    fun glows(grip: Float, knob: Float, phase: Double) {
        val b = (0.75 + 0.25 * sin(phase * 4.2)).toFloat()
        if (gripGlow.enabled != grip > 0) gripGlow.enabled = grip > 0
        if (grip > 0) {
            val k = (0.45f * grip * b).coerceAtMost(1f)
            gripGlowMat.setBaseColor(color(1f * k, 0.85f * k, 0.35f * k, k))
            val r = 0.019f + 0.004f * b
            gripGlow.transform().setScaleVector(Vector3(r, CORK_LEN + 0.01f, r))
        }
        if (knobGlow.enabled != knob > 0) knobGlow.enabled = knob > 0
        if (knob > 0) {
            val k = (0.45f * knob * b).coerceAtMost(1f)
            knobGlowMat.setBaseColor(color(1f * k, 0.6f * k, 0.15f * k, k))
            val r = 0.019f + 0.005f * b
            knobGlow.transform().setScaleVector(Vector3(r, 0.042f, r))
            knobMat.setEmissiveColor(color(0.95f * (0.6f + 0.4f * b), 0.45f * (0.6f + 0.4f * b), 0.08f, 1f))
        }
    }

    /**
     * The rod held at [gripAt] pointing [pointing]; [toward] is where the line pulls (the float or the fish), [load]
     * 0..1 how hard (the bend), [whip] a cast's flex this frame (degrees, + back).
     */
    fun pose(gripAt: Vec3, pointing: Vec3, toward: Vec3?, load: Double, whip: Double) {
        grip = gripAt
        dir = pointing.normalized()
        right = dir.cross(Vec3.UP).normalized().let { if (it == Vec3.ZERO) Vec3(1.0, 0.0, 0.0) else it }
        under = right.cross(dir).normalized() * -1.0
        val zAxis = right.cross(dir).normalized()
        handle.setPos(grip)
        handle.transform().quaternion = quatFromBasis(right, dir, zAxis)
        // The blank: from the winding check to the tip, bent toward the line (more toward the tip).
        val base = grip + dir * 0.26
        val pull = toward?.let { t ->
            val d = (t - base).normalized()
            (d - dir * d.dot(dir))
        } ?: Vec3.ZERO
        val down = Vec3.UP * -1.0 - dir * (-dir.y)
        val sag = down.normalized() * 0.04
        val whipV = zAxis * (sin(Math.toRadians(whip)) * 0.6)
        for (i in 0..BLANK_SEGS) {
            val s = i.toDouble() / BLANK_SEGS
            val along = dir * (BLANK_LEN * s)
            val bend = (pull * (load * 0.55) + sag + whipV) * (BLANK_LEN * s * s)
            nodes[i] = base + along + bend
        }
        // Keep the bent blank its length (a bend draws the tip in).
        for (i in 1..BLANK_SEGS) {
            val seg = (nodes[i] - nodes[i - 1]).normalized() * (BLANK_LEN / BLANK_SEGS)
            nodes[i] = nodes[i - 1] + seg
        }
        for (i in 0 until BLANK_SEGS) {
            val r = R_BASE + (R_TIP - R_BASE) * (i + 0.5) / BLANK_SEGS
            stretch(blank[i], nodes[i], nodes[i + 1], r)
        }
        for ((k, at) in GUIDES.withIndex()) {
            val i = (at * BLANK_SEGS).toInt().coerceIn(0, BLANK_SEGS - 1)
            val p = nodes[i] + zAxis * -0.01
            val g = guides[k]
            g.setPos(p)
            val d = (nodes[i + 1] - nodes[i]).normalized()
            val rx = (right - d * right.dot(d)).normalized()
            g.transform().quaternion = quatFromBasis(rx, d, rx.cross(d))
            g.setScale((0.009 - 0.005 * at).toFloat())
        }
        tip = nodes[BLANK_SEGS]
        tipTop.setPos(tip)
    }

    /**
     * The line from the tip to [end]: a curve that sags by [sag] meters at its middle (slack) and runs straight when
     * tight; red while the fish runs.
     */
    fun line(end: Vec3?, sag: Double, running: Boolean) {
        val on = end != null
        for (s in line) if (s.enabled != on) s.enabled = on
        if (end == null) return
        lineMat.setBaseColor(if (running) color(1f, 0.35f, 0.3f, 0.95f) else color(0.96f, 0.96f, 0.9f, 0.85f))
        val mid = (tip + end) * 0.5 + Vec3(0.0, -sag, 0.0)
        var prev = tip
        for (i in 1..LINE_SEGS) {
            val u = i.toDouble() / LINE_SEGS
            val p = tip * ((1 - u) * (1 - u)) + mid * (2 * u * (1 - u)) + end * (u * u)
            stretch(line[i - 1], prev, p, 0.0008)
            prev = p
        }
    }

    private fun stretch(e: Entity, a: Vec3, b: Vec3, r: Double) {
        val d = b - a
        val len = d.length()
        e.setPos((a + b) * 0.5)
        if (len < 1e-6) return
        e.transform().quaternion = quatFromY(d)
        e.transform().setScaleVector(Vector3(r.toFloat(), len.toFloat(), r.toFloat()))
    }

    companion object {
        const val BLANK_LEN = 2.1
        const val BLANK_SEGS = 14
        const val R_BASE = 0.0062
        const val R_TIP = 0.0016
        const val CRANK_X = -0.032f
        val CORK_MID = Layout.CORK_MID.toFloat()
        /** The cork from 11.5 cm behind the hand to 7 cm in front (its middle at [CORK_MID]). */
        const val CORK_LEN = 0.185f
        const val CRANK_ARM = 0.07f
        const val LINE_SEGS = 16
        val GUIDES = doubleArrayOf(0.12, 0.3, 0.46, 0.6, 0.72, 0.83, 0.92)
    }
}

/**
 * Rings spreading on the water: drawn from a soft ring texture on flat planes, each growing and fading. A cast's
 * landing, the float's nod, a nibble, a bite, the fish's struggle all leave their own.
 */
class Ripples(private val root: Entity) {
    private val plane = MeshResource.createPlane(1f, 1f, 0f).shared()
    private val tex: TextureResource = run {
        val n = 256
        val b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) {
            val r = hypot(x - n / 2.0 + 0.5, y - n / 2.0 + 0.5) / (n / 2.0)
            // A bright crest with a soft dark trough inside it.
            val crest = kotlin.math.exp(-((r - 0.82) / 0.05).let { it * it })
            val trough = 0.35 * kotlin.math.exp(-((r - 0.7) / 0.06).let { it * it })
            val a = (crest * 0.9 + trough * 0.4).coerceIn(0.0, 1.0)
            val c = if (crest >= trough) 255 else 120
            b.setPixel(x, y, android.graphics.Color.argb((a * 255).toInt(), c, c, c))
        }
        TextureResource.create(b).shared()
    }
    private class Ring(val e: Entity, val m: UnlitMaterial, val size: Double, val life: Double, var age: Double, val strength: Double)
    private val rings = ArrayList<Ring>()

    /** [count] rings at [at], spaced a quarter second apart, growing to [size] meters across over [life] seconds. */
    fun spawn(at: Vec3, count: Int = 1, size: Double = 0.8, life: Double = 2.2, strength: Double = 0.7) {
        if (rings.size > 40) return
        for (k in 0 until count) {
            val m = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColorTexture(tex)
                setBaseColor(color(0.92f, 0.96f, 1f, 1f))
                setOpacity(0f)
                setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
            // createPlane lies in x-y facing +z: turned to lie on the water, facing up.
            val e = model(plane, m).apply { setPos(Vector3(at.x.toFloat(), 0.012f + 0.001f * k, at.z.toFloat())); setScale(0.01f); transform().eulerAngles = EulerAngles(-90f, 0f, 0f) }
            root.addChild(e)
            rings += Ring(e, m, size * (1 - 0.18 * k), life, -0.25 * k, strength)
        }
    }

    fun frame(dt: Double) {
        val it = rings.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.age += dt
            if (r.age < 0) continue
            val u = r.age / r.life
            if (u >= 1) { r.e.destroy(); it.remove(); continue }
            val grow = 1 - (1 - u) * (1 - u)
            r.e.setScale((0.05 + r.size * grow).toFloat())
            r.m.setOpacity((r.strength * (1 - u) * (1 - u) * (if (u < 0.08) u / 0.08 else 1.0)).toFloat())
        }
    }

    fun clear() { rings.forEach { it.e.destroy() }; rings.clear() }
}
