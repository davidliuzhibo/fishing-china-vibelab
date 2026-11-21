package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.1 (device test 2026-11-21, 截图「钓鱼1」: the left info panel over the open menu): every set of panels that can be
 * up together, projected to angles from the eye, keeps [PanelLayout.MIN_GAP] degrees apart, and none hides the rod, the
 * net, the bait box, the water where the float lands or the fish at the bank.
 */
class PanelLayoutTest {
    private val phases = listOf("CARD", "INTRO", "PLAYING", "RESULT")

    /** Every combination the stage can show, with what must stay in sight then. */
    private fun everyState(): List<Triple<String, List<PanelLayout.Slot>, Map<String, List<Vec3>>>> = buildList {
        for (ph in phases) for (menu in listOf(false, true)) for (board in listOf(false, true)) for (high in listOf(false, true)) for (note in listOf(false, true))
            for (lineOut in listOf(false, true)) for (nettable in listOf(false, true)) {
                if (!board && high) continue
                if (nettable && !lineOut) continue
                // The board is lifted exactly while the float or the fish is out (tutorial steps 5–7); the net's how-to
                // (NETTABLE) and the steps on land keep it low.
                if (board && high && (!lineOut || nettable)) continue
                if (board && !high && lineOut && !nettable) continue
                val panels = PanelLayout.shown(ph, menu, board, high, note)
                add(Triple("$ph menu=$menu board=$board high=$high note=$note lineOut=$lineOut nettable=$nettable", panels, PanelLayout.targets(ph, lineOut, nettable)))
            }
    }

    @Test
    fun panelsUpTogetherNeverOverlap() {
        val bad = ArrayList<String>()
        var least = 180.0
        for ((name, panels, _) in everyState()) {
            val r = panels.map { it.rect() }
            for (i in r.indices) for (j in i + 1 until r.size) {
                val g = PanelLayout.gap(r[i], r[j])
                least = minOf(least, g)
                if (g < PanelLayout.MIN_GAP) bad += "$name: ${r[i].id}-${r[j].id} ${"%.1f".format(g)}°"
            }
        }
        println("closest pair of panels up together: ${"%.1f".format(least)}°")
        assertTrue(bad.distinct().joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun noPanelHidesTheRodTheNetTheBaitBoxTheFloatOrTheFish() {
        val bad = ArrayList<String>()
        val least = HashMap<String, Double>()
        for ((name, panels, targets) in everyState()) for (s in panels) for ((what, pts) in targets) {
            val g = PanelLayout.gapTo(s.rect(), pts)
            least[what] = minOf(least[what] ?: 180.0, g)
            if (g < PanelLayout.MIN_GAP) bad += "$name: ${s.id} hides the $what (${"%.1f".format(g)}°)"
        }
        println("closest panel to each: " + least.entries.joinToString { "${it.key} ${"%.1f".format(it.value)}°" })
        assertTrue(bad.distinct().take(40).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun theMenuTakesTheLeftPanelsPlaceAndTheLeftPanelFolds() {
        for (ph in phases) for (board in listOf(false, true)) {
            val open = PanelLayout.shown(ph, menu = true, board = board, high = false, note = true).map { it.id }
            assertTrue(PanelLayout.MENU in open)
            assertTrue("$ph: the left panel folds while the menu is open", PanelLayout.HUD !in open)
            assertTrue(PanelLayout.MENU_BTN in open)
        }
        assertEquals(PanelLayout.HUD_SLOT.at, PanelLayout.MENU_SLOT.at)
        // The left panel is up from the lake's card on (its 「换饵」 works there too).
        assertTrue(PanelLayout.HUD in PanelLayout.shown("INTRO", false, false, false, false).map { it.id })
        assertTrue(PanelLayout.HUD !in PanelLayout.shown("CARD", false, false, false, false).map { it.id })
    }

    @Test
    fun theNoteGoesWhenABoardOrACardIsUp() {
        assertTrue(PanelLayout.NOTE in PanelLayout.shown("PLAYING", false, false, false, true).map { it.id })
        assertTrue(PanelLayout.NOTE !in PanelLayout.shown("PLAYING", false, true, false, true).map { it.id })
        for (ph in listOf("CARD", "INTRO", "RESULT")) assertTrue(PanelLayout.NOTE !in PanelLayout.shown(ph, false, false, false, true).map { it.id })
    }

    @Test
    fun theOldPlacesDidOverlap() {
        // 0.5.0: the HUD 0.26 m wide at (-0.5, 0.85, -0.5) turned 35°, pitched -25°, about 0.42 m tall in a fight; the open
        // menu 0.26 x 0.40 at (-0.42, 0.66, -0.38) turned 30°, pitched -45° (screenshot 钓鱼1).
        fun old(id: String, at: Vec3, pitch: Double, yaw: Double, w: Double, h: Double): PanelLayout.Rect {
            val q = Math.toRadians(yaw); val p = Math.toRadians(pitch)
            return PanelLayout.Rect(id, at, Vec3(kotlin.math.cos(q), 0.0, -kotlin.math.sin(q)), Vec3(kotlin.math.sin(p) * kotlin.math.sin(q), kotlin.math.cos(p), kotlin.math.sin(p) * kotlin.math.cos(q)), w, h)
        }
        val hud = old("hud", Vec3(-0.5, 0.85, -0.5), -25.0, 35.0, 0.26, 0.42)
        val menu = old("menu", Vec3(-0.42, 0.66, -0.38), -45.0, 30.0, 0.26, 0.40)
        assertEquals(0.0, PanelLayout.gap(hud, menu), 1e-9)
        // And the old tutorial board straight ahead had the resting rod running through its right side.
        val board = old("tutor", Vec3(0.0, Layout.EYE - kotlin.math.tan(Math.toRadians(6.0)), -1.0), -6.0, 0.0, 0.56, 0.30)
        assertTrue(PanelLayout.gapTo(board, PanelLayout.rodAtRest()) < PanelLayout.MIN_GAP)
    }

    @Test
    fun everyPanelIsInAComfortablePlace() {
        for (s in PanelLayout.ALL) {
            val a = s.rect().angles()
            // Within 66° to either side, 36° below and 30° above the eyes; reading distance 0.7–1.2 m.
            assertTrue("${s.id} yaw ${a[0]}..${a[1]}", a[0] >= -66 && a[1] <= 30)
            assertTrue("${s.id} pitch ${a[2]}..${a[3]}", a[2] >= -36 && a[3] <= 30)
            assertTrue(s.dist in 0.7..1.2)
        }
        // Facing the eye: the panel's normal points back at it.
        for (s in PanelLayout.ALL) {
            val n = s.right.cross(s.up)
            assertTrue("${s.id} faces the eye", n.dot((PanelLayout.EYE - s.at).normalized()) > 0.999)
        }
    }

    @Test
    fun theProbeReportsOverlapsAndOverflow() {
        val lines = PanelLayout.report(listOf(PanelLayout.HUD_SLOT, PanelLayout.MENU_BTN_SLOT), mapOf("hud" to (0.34 to 0.30)), PanelLayout.targets("PLAYING", true, false))
        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("panels overlaps: none") })
        assertTrue(lines.any { it.startsWith("panels hide nothing") })
        val over = PanelLayout.report(listOf(PanelLayout.HUD_SLOT), mapOf("hud" to (0.34 to 0.45)), emptyMap())
        assertTrue(over.any { "OVERFLOW" in it })
        // The old menu under the left panel: reported.
        val both = PanelLayout.report(listOf(PanelLayout.HUD_SLOT, PanelLayout.MENU_SLOT), emptyMap(), emptyMap())
        assertTrue(both.any { it.startsWith("panels OVERLAP") })
    }
}
