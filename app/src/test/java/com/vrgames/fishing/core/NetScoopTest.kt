package com.vrgames.fishing.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The landing net's standard scoop (kept from v0.4): hoop under the fish, press down, lift. */
class NetScoopTest {
    @Test
    fun theNetTakesTheFishWhenBroughtUpUnderIt() {
        val s = NetScoop()
        val fish = Vec3(0.1, 0.0, -1.5)
        var t = 0.0
        fun at(p: Vec3) = s.update(t, p, fish).also { t += 1.0 / 72 }
        assertFalse(at(Vec3(0.7, 0.2, -1.5)))
        for (k in 0..10) assertFalse(at(Vec3(0.3, -0.02 * k, -1.5)))
        var got = false
        for (k in 0..10) got = got || at(Vec3(0.3, -0.2 + 0.03 * k, -1.5))
        assertTrue(got)
    }

    @Test
    fun aPressAndLiftNearTheFishAboveTheWaterAlsoScoops() {
        val s = NetScoop()
        val fish = Vec3(0.0, 0.02, -1.45)
        var t = 0.0
        var got = false
        for (k in 0..15) { got = got || s.update(t, Vec3(0.3, 0.3 - 0.01 * k, -1.45), fish); t += 0.02 }
        assertFalse(got)
        for (k in 0..12) { got = got || s.update(t, Vec3(0.3, 0.15 + 0.01 * k, -1.45), fish); t += 0.02 }
        assertTrue(got)
    }

    @Test
    fun theNetAwayFromTheFishOrStillDoesNotScoop() {
        val s = NetScoop()
        val fish = Vec3(0.0, 0.02, -1.45)
        var t = 0.0
        for (k in 0..30) { assertFalse(s.update(t, Vec3(1.0, 0.3 - 0.01 * (if (k < 15) k else 30 - k), -1.45), fish)); t += 0.02 }
        for (k in 0..100) { assertFalse(s.update(t, Vec3(0.2, 0.0, -1.45), fish)); t += 0.02 }
    }

    @Test
    fun theNetReachesTheFishFromItsGrip() {
        // Picked up at its rest grip: the hoop goes out over a metre, at or under the water.
        val hoop = NetScoop.Pose.hoop(Layout.NET_GRIP, Layout.SHOULDER_L)
        assertTrue("ahead: ${hoop.z}", hoop.z < -1.0)
        assertTrue("at or under the water: ${hoop.y}", hoop.y < 0.15)
        val up = NetScoop.Pose.hoop(Layout.NET_GRIP + Vec3(0.0, 0.15, 0.0), Layout.SHOULDER_L)
        assertTrue("lifted ${up.y - hoop.y}", up.y - hoop.y > 0.3)
        // Every place the tired fish can be (straight ahead to 25° aside, from a net's length in to the bank).
        for (yaw in listOf(-25.0, 0.0, 25.0)) for (d in listOf(Trip.NET_DIST, 1.4, 1.0, Trip.REEL_LAND_DIST)) {
            val want = Layout.fishForNet(yaw, d) + Vec3(0.0, -0.18, 0.0)
            val h = NetScoop.Pose.handFor(want, Layout.SHOULDER_L, Layout.NET_GRIP)
            assertTrue("yaw $yaw d $d: hoop at ${NetScoop.Pose.hoop(h, Layout.SHOULDER_L)}", NetScoop.Pose.hoop(h, Layout.SHOULDER_L).distanceTo(want) < 0.08)
        }
    }
}
