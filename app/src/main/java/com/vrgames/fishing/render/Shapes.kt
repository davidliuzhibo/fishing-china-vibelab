package com.vrgames.fishing.render

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.vrgames.fishing.core.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun Vec3.toVector3() = Vector3(x.toFloat(), y.toFloat(), z.toFloat())

/** The entity's transform; a detached one if it is gone (a frame queued before quitting must not crash). */
fun Entity.transform(): TransformComponent = components[TransformComponent::class.java] ?: TransformComponent()

fun Entity.setPos(p: Vector3) { transform().setPosition(p) }
fun Entity.setPos(p: Vec3) { transform().setPosition(p.toVector3()) }
fun Entity.setScale(s: Float) { transform().setScaleVector(Vector3(s)) }

fun color(r: Float, g: Float, b: Float, a: Float = 1f) = Color4(r, g, b, a)

fun unlit(c: Color4, blending: BlendingMode = BlendingMode.OPAQUE): UnlitMaterial =
    UnlitMaterial.create(blending).apply { setBaseColor(c) }

fun pbr(
    base: Color4,
    metallic: Float = 0f,
    roughness: Float = 0.6f,
    emissive: Color4? = null,
    blending: BlendingMode = BlendingMode.OPAQUE,
): PhysicallyBasedMaterial = PhysicallyBasedMaterial.create(blending).apply {
    setBaseColor(base)
    setMetallic(metallic)
    setRoughness(roughness)
    // Full immersion has no room light to fill the shadows: a share of the base color glows as ambient.
    setEmissiveColor(emissive ?: Color4(base.red * ambient, base.green * ambient, base.blue * ambient, 1f))
}

/**
 * How much of a surface's own color it shows without any light (see [pbr]); set per time of day
 * before the shapes are made (low at night, so the lit lanterns stand out).
 */
var ambient = 0.45f

/** Additive light: brightens what is behind it, never darkens. [c]'s alpha scales the strength. */
fun glow(c: Color4): UnlitMaterial =
    UnlitMaterial.create(BlendingMode.ADD).apply {
        setBaseColor(Color4(c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha))
    }

/**
 * Additive back faces only. On a shell slightly larger than an object, the object hides the middle,
 * so only a thin glow at the silhouette shows: an outline without a custom shader.
 */
fun rim(c: Color4): UnlitMaterial = glow(c).apply { setCullingMode(MaterialCullingMode.FRONT) }

fun model(mesh: MeshResource, material: Material) = ModelEntity(mesh, material)

/** Makes a resource app-owned, so destroying an entity that uses it does not release it. */
fun <T : Resource> T.shared(): T = apply { toGlobal() }

/** Rotation that turns +Y onto [dir]. */
fun quatFromY(dir: Vec3): Quat {
    val d = dir.normalized()
    val dot = d.y.coerceIn(-1.0, 1.0)
    if (dot > 0.99999) return Quat(0f, 0f, 0f, 1f)
    if (dot < -0.99999) return Quat(1f, 0f, 0f, 0f)
    // axis = Y x d = (d.z, 0, -d.x)
    val ax = d.z
    val az = -d.x
    val len = sqrt(ax * ax + az * az)
    val half = acos(dot) / 2
    val s = sin(half) / len
    return Quat((ax * s).toFloat(), 0f, (az * s).toFloat(), cos(half).toFloat())
}

/** Rotation whose local x, y, z axes are [x], [y], [z] (an orthonormal right-handed basis). */
fun quatFromBasis(x: Vec3, y: Vec3, z: Vec3): Quat {
    val m00 = x.x; val m10 = x.y; val m20 = x.z
    val m01 = y.x; val m11 = y.y; val m21 = y.z
    val m02 = z.x; val m12 = z.y; val m22 = z.z
    val tr = m00 + m11 + m22
    val qx: Double; val qy: Double; val qz: Double; val qw: Double
    if (tr > 0) {
        val s = sqrt(tr + 1.0) * 2
        qw = 0.25 * s; qx = (m21 - m12) / s; qy = (m02 - m20) / s; qz = (m10 - m01) / s
    } else if (m00 > m11 && m00 > m22) {
        val s = sqrt(1.0 + m00 - m11 - m22) * 2
        qw = (m21 - m12) / s; qx = 0.25 * s; qy = (m01 + m10) / s; qz = (m02 + m20) / s
    } else if (m11 > m22) {
        val s = sqrt(1.0 + m11 - m00 - m22) * 2
        qw = (m02 - m20) / s; qx = (m01 + m10) / s; qy = 0.25 * s; qz = (m12 + m21) / s
    } else {
        val s = sqrt(1.0 + m22 - m00 - m11) * 2
        qw = (m10 - m01) / s; qx = (m02 + m20) / s; qy = (m12 + m21) / s; qz = 0.25 * s
    }
    return Quat(qx.toFloat(), qy.toFloat(), qz.toFloat(), qw.toFloat())
}
