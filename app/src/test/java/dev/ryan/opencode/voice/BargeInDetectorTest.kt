package dev.ryan.opencode.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Barge-in detection.
 *
 * The failure mode this guards against is specific and nasty: the assistant's own
 * voice, played through the phone speaker, registers as loud input, so a naive
 * threshold makes it interrupt itself in a loop. These tests pin the two
 * properties that prevent that — a relative (noise-floor) threshold, and a
 * requirement for *consecutive* frames.
 */
class BargeInDetectorTest {

    /** Speaker bleed: loud but ragged, with gaps — must NOT trigger. */
    @Test
    fun `ragged speaker bleed does not trigger barge-in`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        // Calibrate on the speaker's own level, then play ragged audio at roughly
        // that same level: this must never look like an interjection.
        repeat(6) { d.onRms(-18f) }
        val bleed = listOf(-16f, -19f, -15f, -20f, -14f, -18f, -17f, -16f, -19f, -15f, -18f, -17f)
        assertFalse("speaker bleed must not interrupt", bleed.any { d.onRms(it) })
    }

    /** Real speech: sustained well above the floor — must trigger exactly once. */
    @Test
    fun `sustained speech above the noise floor triggers once`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        // Establish the speaker's baseline, then the user speaks over it.
        repeat(6) { d.onRms(-32f) }
        val fired = (1..10).map { d.onRms(-8f) }
        assertTrue("sustained speech must trigger", fired.any { it })
        assertEquals(1, fired.count { it })
    }

    @Test
    fun `detector disarms after firing so it cannot re-trigger`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        repeat(6) { d.onRms(-32f) }
        val first = (1..5).map { d.onRms(-5f) }
        assertTrue(first.any { it })
        // Continued loud audio must be ignored: the user already interrupted, and
        // re-firing would cut off their reply.
        val after = (1..20).map { d.onRms(-5f) }
        assertFalse("must not re-trigger", after.any { it })
    }

    @Test
    fun `a new utterance re-arms the detector`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        repeat(6) { d.onRms(-32f) }
        assertTrue((1..5).any { d.onRms(-5f) })
        // The assistant resumes speaking; a fresh interjection must be detectable,
        // otherwise barge-in would only ever work once per session.
        d.onSpeechStarted()
        repeat(6) { d.onRms(-32f) }
        assertTrue("must re-arm for the next turn", (1..5).any { d.onRms(-5f) })
    }

    @Test
    fun `detector is disarmed until speech starts`() {
        val d = SpeechScript.BargeInDetector()
        assertFalse(d.onRms(0f))
        assertFalse(d.onRms(0f))
        assertFalse(d.onRms(0f))
    }

    @Test
    fun `a gap resets the streak`() {
        val d = SpeechScript.BargeInDetector(triggerAfter = 3)
        d.onSpeechStarted()
        repeat(6) { d.onRms(-30f) }
        assertFalse(d.onRms(-6f))
        assertFalse(d.onRms(-6f))
        // Quiet frame breaks the run...
        assertFalse(d.onRms(-30f))
        // ...so two more loud frames are not enough on their own.
        assertFalse(d.onRms(-6f))
        assertFalse(d.onRms(-6f))
        assertTrue(d.onRms(-6f))
    }

    @Test
    fun `threshold adapts to a loud room`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        // Noisy room: speaker baseline around -20.
        repeat(6) { d.onRms(-20f) }
        // The user's voice at -12 is only ~8dB over that floor.
        val out = (1..6).map { d.onRms(-12f) }
        assertTrue("should still detect real speech in a noisy room", out.any { it })
    }

    @Test
    fun `resetBargeIn clears state`() {
        val d = SpeechScript.BargeInDetector()
        d.onSpeechStarted()
        repeat(6) { d.onRms(-30f) }
        d.resetBargeIn()
        assertFalse("must be disarmed after reset", d.onRms(0f))
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected, actual)
}
