package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

/**
 * Placement is locked (2026-11-23: tables moved in new versions without being asked and ended inside the player's
 * body). The rod in its holder, the bait box, the creel and the landing net must stay as in 0.5.2, the version passed
 * on the device 2026-11-21 (source copy Pico_Vibe_Lab/提交包_20261121b/FishingChina). The seat's frame has the eye at
 * [Layout.EYE] over its floor; the stage lifts it to the EyeHeight module's measured eye (floor = eye − EYE) at the
 * eyes' x/z, turned once to the mean heading.
 */
class PlacementLockTest {
    private val src = File("src/main/java/com/vrgames/fishing")
    private val stage = File(src, "content/LakeStage.kt").readText()
    private val view = File(src, "render/LakeView.kt").readText()

    companion object {
        /** 0.5.2, seat frame (eye at 1.2 m over the floor, x right, -z ahead). */
        const val EYE = 1.2
        // v0.6.0 (approved plan 2026-11-25): 8 cm further out and 4 cm lower; the hands are corrected by core.RenderOffset, so it is seen ~0.34 m ahead.
        val REST_GRIP = doubleArrayOf(0.17, EYE - 0.29, -0.60)
        const val READY_PITCH = 32.0
        const val PIVOT_BACK = 0.30
        const val BUTT = 0.12
        val NET_REST_BUTT = doubleArrayOf(-0.15, EYE - 0.42, -0.42)
        val NET_REST_HOOP = doubleArrayOf(-0.35, 0.08, -1.14)
        val BAIT = doubleArrayOf(-0.40, 0.56, -0.44)
        val BASKET = doubleArrayOf(-0.62, 0.45, -0.4)
        /** The bait box model: scale 0.12, baitbox.glb half depth 0.5 at scale 1. */
        const val BAIT_SCALE = 0.12
        const val BAIT_HALF_D = 0.5
        const val BODY_CLEAR = 0.30
    }

    private fun same(name: String, want: DoubleArray, got: Vec3) {
        assertEquals("$name.x", want[0], got.x, 1e-3); assertEquals("$name.y", want[1], got.y, 1e-3); assertEquals("$name.z", want[2], got.z, 1e-3)
    }

    @Test
    fun tackleStaysWhere052PutIt() {
        assertEquals(EYE, Layout.EYE, 1e-9)
        same("REST_GRIP", REST_GRIP, Layout.REST_GRIP)
        assertEquals(READY_PITCH, Layout.READY_PITCH, 1e-9)
        assertEquals(PIVOT_BACK, Layout.PIVOT_BACK, 1e-9)
        assertEquals(BUTT, Layout.BUTT, 1e-9)
        same("NET_REST_BUTT", NET_REST_BUTT, Layout.NET_REST_BUTT)
        same("NET_REST_HOOP", NET_REST_HOOP, Layout.NET_REST_HOOP)
        same("BAIT", BAIT, Layout.BAIT)
        same("BASKET", BASKET, Layout.BASKET)
        // The holder's fork: 0.30 m out along the rod pitched 32° up from the grip.
        val a = Math.toRadians(READY_PITCH)
        same("PIVOT", doubleArrayOf(REST_GRIP[0], REST_GRIP[1] + PIVOT_BACK * kotlin.math.sin(a), REST_GRIP[2] - PIVOT_BACK * cos(a)), Layout.PIVOT)
        // Where the code puts them.
        assertTrue(view.contains("holder.setPos(PIVOT)") && view.contains("setPos(Layout.BAIT.toVector3())") && view.contains("basket.setPos(Layout.BASKET.toVector3())"))
        assertTrue(view.contains("it.setScale(${BAIT_SCALE}f)"))
        // The seat: floor = measured eye − EYE, at the eyes' x/z, turned once.
        assertTrue(stage.contains("floorY = eyes.value!! - LakeView.EYE.toFloat()"))
        assertTrue(stage.contains("it.setPos(Vector3(eye.x, floorY, eye.z))") && stage.contains("it.transform().eulerAngles = EulerAngles(0f, facing, 0f)"))
        assertTrue(view.contains("const val EYE = Layout.EYE"))
    }

    @Test
    fun nothingSolidWithin30cmOfTheEyes() {
        // Ahead of the eyes (-z), the nearest point of each solid thing at body height (the bank, boat deck and raft
        // under the seat are ground, their tops at most 0.41 m over the floor, and are left out).
        val butt = -(REST_GRIP[2] + BUTT * cos(Math.toRadians(READY_PITCH)))
        val net = -NET_REST_BUTT[2]
        val bait = -BAIT[2] - BAIT_HALF_D * BAIT_SCALE
        val pivot = -Layout.PIVOT.z
        for ((name, d) in listOf("rod butt" to butt, "net handle end" to net, "bait box near edge" to bait, "holder" to pivot)) assertTrue("$name $d m", d >= BODY_CLEAR)
        for (eye in listOf(1.65, 1.25)) {
            val floor = eye - EYE
            println("placement (eye $eye): scene floor ${"%.2f".format(floor)} m; rod grip (${REST_GRIP[0]}, ${"%.2f".format(floor + REST_GRIP[1])}, ${REST_GRIP[2]}) = 0.29 m below the eyes, 0.60 m ahead (seen ~0.34); " +
                "nearest: bait box ${"%.3f".format(bait)} m, rod butt ${"%.3f".format(butt)} m, net end ${"%.2f".format(net)} m ahead")
        }
    }
}
