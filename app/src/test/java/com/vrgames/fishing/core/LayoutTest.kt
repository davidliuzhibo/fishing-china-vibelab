package com.vrgames.fishing.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

/**
 * v0.5's places around the seat (device test 2026-11-20: the grip at the chest; the net jumping into a resting hand;
 * gaze-pinches on the HUD, the menu or the bait box could land on 0.4's big net collider, which contained them).
 */
class LayoutTest {
    private val eye = Vec3(0.0, Layout.EYE, 0.0)

    /** Degrees below the eyes and to the right, seen from the seat. */
    private fun below(p: Vec3) = Math.toDegrees(atan2(Layout.EYE - p.y, -p.z))
    private fun right(p: Vec3) = Math.toDegrees(atan2(p.x, -p.z))

    @Test
    fun sightLinesToTheHudTheMenuAndTheBaitBoxMissTheNet() {
        val boxes = Layout.netRestBoxes()
        // v0.5.1: the left panel's slot (also the menu's) and the menu button, corners and centres (core.PanelLayout).
        val targets = HashMap<String, Vec3>()
        for (s in listOf(PanelLayout.HUD_SLOT, PanelLayout.MENU_BTN_SLOT)) {
            val r = s.rect()
            targets[s.id] = r.c
            for (u in listOf(-0.5, 0.5)) for (w in listOf(-0.5, 0.5)) targets["${s.id} $u $w"] = r.point(u, w)
        }
        targets["bait"] = Layout.BAIT
        targets["basket"] = Layout.BASKET
        for ((name, p) in targets) for ((k, b) in boxes.withIndex()) {
            assertFalse("$name inside net box $k", Layout.contains(b, p))
            assertFalse("the sight line to $name crosses net box $k", Layout.segmentHits(eye, p, b))
        }
    }

    @Test
    fun theOldBigNetColliderDidCoverThem() {
        // 0.4.0's single 0.5×1.05×0.5 box over the whole net (scale 1.29), at its rest then: it held the HUD, menu and
        // bait box (their 0.5.0 places).
        val butt = Vec3(-0.20, Layout.EYE - 0.46, -0.36)
        val hoop = Vec3(-0.42, 0.08, -1.10)
        val s = hoop.distanceTo(butt) / 0.79
        val (x, y, z) = Layout.netBasis(hoop - butt)
        val old = Layout.Box(butt + y * (0.5 * s), listOf(x, y, z), doubleArrayOf(0.25 * s, 0.525 * s, 0.25 * s))
        assertTrue(Layout.contains(old, Vec3(-0.42, 0.66, -0.38)))
        assertTrue(Layout.contains(old, Vec3(-0.42, 0.62, -0.5)))
        assertTrue(Layout.contains(old, Vec3(-0.5, 0.85, -0.5)))
    }

    @Test
    fun theRodsGripIsInSightLookingStraightAhead() {
        val glow = Layout.gripTarget(Layout.REST_GRIP, Layout.dirOf(Layout.READY_PITCH, 0.0))
        assertTrue("below ${below(glow)}°", below(glow) in 20.0..33.0)
        assertTrue("right ${right(glow)}°", right(glow) in 12.0..24.0)
        // 13 cm further out than 0.4's (0.20, eye−0.30, −0.39).
        assertTrue(Layout.REST_GRIP.z <= -0.5)
        // The butt reaches 12 cm behind the hand: well in front of the chest.
        val butt = Layout.REST_GRIP - Layout.dirOf(Layout.READY_PITCH, 0.0) * Layout.BUTT
        assertTrue("butt at z ${butt.z}", butt.z < -0.4)
    }

    @Test
    fun theGripAndTheNetAreInReach() {
        assertTrue(Layout.REST_GRIP.distanceTo(Layout.SHOULDER_R) < 0.72)
        assertTrue(Layout.NET_GRIP.distanceTo(Layout.SHOULDER_L) < 0.72)
        // In WAIT the rod pivots to 14°: its grip stays in reach for the lift that sets the hook.
        assertTrue(Layout.restGrip(Layout.WAIT_PITCH, 10.0).distanceTo(Layout.SHOULDER_R) < 0.72)
    }

    @Test
    fun theNetsGripIsClearOfAHandRestingOnTheThigh() {
        val lap = Vec3(-0.2, Layout.EYE - 0.6, -0.35)
        assertTrue("${Layout.NET_GRIP.distanceTo(lap)}", Layout.NET_GRIP.distanceTo(lap) > 0.15)
        assertFalse(Layout.netRestBoxes().any { Layout.contains(it, lap) })
    }

    @Test
    fun theTutorialBoardDoesNotHideTheFloatOrTheFishWhenUp() {
        // The float lands 4–16 m out (seen 4–17° below the eyes); the board goes up to 10° above them meanwhile
        // (v0.5.1: every panel against the float's water in PanelLayoutTest).
        val r = PanelLayout.TUTOR_HIGH.rect()
        for (d in listOf(4.0, 8.0, 16.0)) assertTrue(PanelLayout.gapTo(r, listOf(Vec3(0.0, 0.0, -d))) > PanelLayout.MIN_GAP)
        assertTrue(PanelLayout.gapTo(r, PanelLayout.floatArea()) > PanelLayout.MIN_GAP)
    }
}
