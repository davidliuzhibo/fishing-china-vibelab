package com.vrgames.fishing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5's tutorial moves on only when the game reports the real thing done (never on a timer or out of order); v0.5.1
 * starts it with the bait (8 steps).
 */
class TutorialTest {
    private val inOrder = listOf(
        Tutorial.Ev.BAIT to 2, Tutorial.Ev.ROD_TAKEN to 3, Tutorial.Ev.COCKED to 4, Tutorial.Ev.CAST to 4, Tutorial.Ev.FLOAT_LANDED to 5,
        Tutorial.Ev.BITE to 6, Tutorial.Ev.HOOKED to 7, Tutorial.Ev.NETTABLE to 8, Tutorial.Ev.FISH_LANDED to 9,
    )

    @Test
    fun theStepsFollowTheRealEventsInOrder() {
        val t = Tutorial()
        t.start()
        assertEquals(1, t.step)
        assertEquals("第 1 步／共 8 步 · 选鱼饵", t.header)
        for ((e, want) in inOrder) { t.on(e); assertEquals("after $e", want, t.step) }
        assertEquals("完成", t.header)
        assertTrue(t.active)
    }

    @Test
    fun theFirstStepIsTheBaitAndMovesOnWhenOneIsPickedOrOnKnowItAlready() {
        val t = Tutorial()
        t.start()
        assertEquals(Tutorial.BAIT, t.step)
        val text = Tutorial.STEPS[0].text
        assertTrue(text.contains("左边面板") && text.contains("「换饵」") && text.contains("翘嘴要用亮片") && text.contains("「知道了」"))
        assertEquals("tut0", Tutorial.STEPS[0].voice)
        // Picking a bait (the left panel's 「换饵」) or 「知道了」 both send the same event.
        t.on(Tutorial.Ev.BAIT)
        assertEquals(2, t.step)
        assertEquals("第 2 步／共 8 步 · 拿起鱼竿", t.header)
        // A second pick later moves nothing.
        t.on(Tutorial.Ev.BAIT)
        assertEquals(2, t.step)
        // The completion page names the bait too.
        assertTrue(Tutorial.STEPS[Tutorial.TOTAL].text.contains("选饵 → 拿竿"))
    }

    @Test
    fun takingTheRodAtTheBaitStepGoesOnToRaisingIt() {
        val t = Tutorial()
        t.start()
        t.on(Tutorial.Ev.ROD_TAKEN)
        assertEquals(3, t.step)
    }

    @Test
    fun eventsOutOfOrderMoveNothing() {
        val t = Tutorial()
        t.start()
        // Nothing but a bait (or taking the rod) moves step 1.
        for (e in Tutorial.Ev.entries - Tutorial.Ev.BAIT - Tutorial.Ev.ROD_TAKEN) { t.on(e); assertEquals("$e at step 1", 1, t.step) }
        t.on(Tutorial.Ev.BAIT)
        // Nothing but taking the rod moves step 2.
        for (e in Tutorial.Ev.entries - Tutorial.Ev.ROD_TAKEN) { t.on(e); assertEquals("$e at step 2", 2, t.step) }
        t.on(Tutorial.Ev.ROD_TAKEN)
        // At step 3 a bite, a hook, the net, a landing: nothing (only the cock).
        for (e in listOf(Tutorial.Ev.CAST, Tutorial.Ev.FLOAT_LANDED, Tutorial.Ev.BITE, Tutorial.Ev.HOOKED, Tutorial.Ev.NETTABLE, Tutorial.Ev.FISH_LANDED)) { t.on(e); assertEquals("$e at step 3", 3, t.step) }
        t.on(Tutorial.Ev.COCKED); t.on(Tutorial.Ev.CAST)
        assertEquals(4, t.step)
        // The float has not landed yet: a bite cannot have happened, nothing moves.
        t.on(Tutorial.Ev.BITE); t.on(Tutorial.Ev.NETTABLE)
        assertEquals(4, t.step)
    }

    @Test
    fun setbacksGoBackToTheStepNeededAgainWithAHint() {
        val t = Tutorial()
        t.start()
        t.on(Tutorial.Ev.BAIT); t.on(Tutorial.Ev.ROD_TAKEN); t.on(Tutorial.Ev.COCKED)
        t.on(Tutorial.Ev.FAIL_SLOW)
        assertEquals(3, t.step)
        assertEquals(Tutorial.FAIL_SLOW, t.hint?.text)
        assertEquals("fail_slow", t.hint?.voice)
        t.on(Tutorial.Ev.COCKED)
        assertNull("the hint goes with the step", t.hint)
        t.on(Tutorial.Ev.FAIL_LOST)
        assertEquals(3, t.step)
        assertEquals("fail_lost", t.hint?.voice)
        t.on(Tutorial.Ev.COCKED); t.on(Tutorial.Ev.FLOAT_LANDED)
        assertEquals(5, t.step)
        // Striking at a nibble: told, the step stays.
        assertTrue(t.on(Tutorial.Ev.EARLY))
        assertEquals(5, t.step)
        assertEquals("fail_early", t.hint?.voice)
        t.on(Tutorial.Ev.BITE); t.on(Tutorial.Ev.MISSED)
        assertEquals(5, t.step)
        t.on(Tutorial.Ev.BITE); t.on(Tutorial.Ev.HOOKED); t.on(Tutorial.Ev.NETTABLE)
        assertEquals(8, t.step)
        t.on(Tutorial.Ev.AWAY)
        assertEquals(7, t.step)
        // A broken line: take the rod again (not back to the bait).
        t.on(Tutorial.Ev.BREAK)
        assertEquals(2, t.step)
        assertEquals("snapped", t.hint?.voice)
    }

    @Test
    fun theReelHelpComesOnceAtTheReelingStep() {
        val t = Tutorial()
        t.start()
        assertFalse(t.reelHelp())
        for ((e, _) in inOrder.take(7)) t.on(e)
        assertEquals(Tutorial.REEL, t.step)
        assertTrue(t.reelHelp())
        assertEquals("tut6b", t.hint?.voice)
        assertFalse(t.reelHelp())
    }

    @Test
    fun theNetIsOptionalReelingItInFinishesToo() {
        val t = Tutorial()
        t.start()
        for ((e, _) in inOrder.take(8)) t.on(e)
        assertEquals(8, t.step)
        t.on(Tutorial.Ev.FISH_LANDED)
        assertEquals(9, t.step)
    }

    @Test
    fun theBoardIsLiftedExactlyWhileTheFloatOrFishIsOut() {
        // Waiting for the bite, striking, reeling.
        assertEquals(listOf("等鱼咬钩", "提竿", "收线"), Tutorial.OUT.map { Tutorial.STEPS[it - 1].title })
        assertEquals("抄网（可选）", Tutorial.NET.title)
    }

    @Test
    fun everyStepHasItsTextAndVoiceAndTheFailHintsAreThere() {
        assertEquals(Tutorial.TOTAL + 1, Tutorial.STEPS.size)
        // Step k (from 2) keeps 0.5.0's voice tut(k-1); the bait step is tut0.
        for ((k, s) in Tutorial.STEPS.withIndex()) { assertTrue(s.text.isNotBlank()); assertEquals("tut$k", s.voice) }
        assertNotNull(Tutorial.ofTripEvent("bite"))
        assertEquals(Tutorial.Ev.FISH_LANDED, Tutorial.ofTripEvent("landed"))
        assertNull(Tutorial.ofTripEvent("run"))
    }
}
