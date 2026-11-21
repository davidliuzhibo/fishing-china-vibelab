package com.vrgames.fishing.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where things are around the seat (metres; the eye at [EYE] over the scene's floor, x right, -z out over the water;
 * the stage places the scene from the measured eyes, core.EyeHeight). Kept here, out of the renderer, so the tests can
 * check reach, sight lines and colliders.
 */
object Layout {
    const val EYE = 1.2

    /**
     * v0.5 (device test 2026-11-20: 「握把还是在胸口」): the rod's grip in its holder 13 cm further out and 5 cm higher
     * than 0.4 (0.20, eye−0.30, −0.39), so it is in sight looking straight ahead (about 27° below, 19° right).
     */
    val REST_GRIP = Vec3(0.17, EYE - 0.25, -0.52)
    const val READY_PITCH = 32.0
    const val WAIT_PITCH = 14.0
    const val FIGHT_PITCH = 48.0
    /** The holder's fork: the rod rests and pivots there, [PIVOT_BACK] ahead of the grip. */
    const val PIVOT_BACK = 0.30
    val PIVOT = REST_GRIP + dirOf(READY_PITCH, 0.0) * PIVOT_BACK
    /** The cork's middle along the rod from the grip point (v0.5: the butt reaches 12 cm behind the hand, was 20). */
    const val CORK_MID = -0.0225
    const val BUTT = 0.12
    /** A palm above this counts as over the shoulder for the cock (15 cm below the eyes). */
    val COCK_Y = EYE - 0.15
    val SHOULDER_L = Vec3(-0.18, EYE - 0.25, 0.05)
    val SHOULDER_R = Vec3(0.18, EYE - 0.25, 0.05)

    /** A unit direction [pitch] degrees up from level and [yaw] degrees to the right of straight ahead. */
    fun dirOf(pitch: Double, yaw: Double): Vec3 {
        val p = Math.toRadians(pitch); val y = Math.toRadians(yaw)
        return Vec3(sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    /** The grip when the rod rests in its holder pointing [pitch]/[yaw]. */
    fun restGrip(pitch: Double, yaw: Double) = PIVOT - dirOf(pitch, yaw) * PIVOT_BACK

    /** Where a hand closes on the rod held at [grip] pointing [dir] (the cork's middle, where the glow is). */
    fun gripTarget(grip: Vec3, dir: Vec3) = grip + dir.normalized() * CORK_MID

    // ---- The landing net (v0.5: a little further from a hand resting on the thigh, clear of the panels' sight lines) ----

    val NET_REST_BUTT = Vec3(-0.15, EYE - 0.42, -0.42)
    val NET_REST_HOOP = Vec3(-0.35, 0.08, -1.14)
    /** The net model's handle end to hoop centre at scale 1, and where along it a hand takes it. */
    const val NET_MODEL_REACH = 0.79
    const val NET_GRIP_AT = 0.1
    val NET_SCALE = NET_REST_HOOP.distanceTo(NET_REST_BUTT) / NET_MODEL_REACH
    val NET_AXIS = (NET_REST_HOOP - NET_REST_BUTT).normalized()
    val NET_GRIP = NET_REST_BUTT + NET_AXIS * (NET_GRIP_AT * NET_SCALE)
    /** The net's two colliders in its own frame (y along the handle from its end; metres at scale 1): full sizes. */
    val NET_POLE_C = Vec3(0.0, 0.31, 0.0)
    val NET_POLE_SIZE = Vec3(0.07, 0.62, 0.07)
    val NET_HOOP_C = Vec3(0.0, 0.79, 0.0)
    val NET_HOOP_SIZE = Vec3(0.40, 0.40, 0.30)

    /** The net's axes for a handle running [d]: x across (level), y along the handle, z = x × y. */
    fun netBasis(d: Vec3): Triple<Vec3, Vec3, Vec3> {
        val y = d.normalized()
        val x = y.cross(Vec3.UP).normalized().let { if (it == Vec3.ZERO) Vec3(1.0, 0.0, 0.0) else it }
        return Triple(x, y, x.cross(y))
    }

    /** The tired fish comes up within this yaw of straight ahead, at most [NET_FISH] out (in the net's reach). */
    const val NET_FISH_YAW = 25.0
    const val NET_FISH = 1.45
    /** The nearest the fish comes before it is landed (the net still reaches it there). */
    const val NET_FISH_MIN = 1.0

    /**
     * Where the tired fish is at the bank (at the surface): [NET_FISH] ahead when it can first be netted, hauled in
     * toward the bank as it is reeled closer (v0.5: reeling on lands it without the net).
     */
    fun fishForNet(castYaw: Double, distance: Double): Vec3 {
        val a = Math.toRadians(castYaw.coerceIn(-NET_FISH_YAW, NET_FISH_YAW))
        val d = (distance / Trip.NET_DIST * NET_FISH).coerceIn(NET_FISH_MIN, NET_FISH)
        return Vec3(d * sin(a), 0.0, -d * cos(a))
    }

    // ---- The things beside the seat (centres; v0.5.1: the panels' places are in core.PanelLayout) ----

    /** v0.5.1: 6 cm lower and 6 cm nearer (was (-0.42, 0.62, -0.5)): its far edge clear under the left panel. */
    val BAIT = Vec3(-0.40, 0.56, -0.44)
    val BASKET = Vec3(-0.62, 0.45, -0.4)

    /** An oriented box: centre, unit axes, half extents. */
    class Box(val c: Vec3, val ax: List<Vec3>, val half: DoubleArray)

    /** The net's colliders resting on its rest. */
    fun netRestBoxes(): List<Box> {
        val (x, y, z) = netBasis(NET_AXIS)
        fun box(c: Vec3, size: Vec3) = Box(
            NET_REST_BUTT + (x * c.x + y * c.y + z * c.z) * NET_SCALE, listOf(x, y, z),
            doubleArrayOf(size.x / 2 * NET_SCALE, size.y / 2 * NET_SCALE, size.z / 2 * NET_SCALE),
        )
        return listOf(box(NET_POLE_C, NET_POLE_SIZE), box(NET_HOOP_C, NET_HOOP_SIZE))
    }

    /** Whether the segment from [a] to [b] passes through [box] (slab test). */
    fun segmentHits(a: Vec3, b: Vec3, box: Box): Boolean {
        val d = b - a
        var t0 = 0.0; var t1 = 1.0
        val p = box.c - a
        for (i in 0..2) {
            val e = box.ax[i].dot(p); val f = box.ax[i].dot(d)
            if (abs(f) > 1e-12) {
                var ta = (e + box.half[i]) / f; var tb = (e - box.half[i]) / f
                if (ta > tb) { val s = ta; ta = tb; tb = s }
                t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
                if (t0 > t1) return false
            } else if (-e - box.half[i] > 0 || -e + box.half[i] < 0) return false
        }
        return true
    }

    /** Whether [p] is inside [box]. */
    fun contains(box: Box, p: Vec3): Boolean {
        val r = p - box.c
        for (i in 0..2) if (abs(box.ax[i].dot(r)) > box.half[i]) return false
        return true
    }
}
