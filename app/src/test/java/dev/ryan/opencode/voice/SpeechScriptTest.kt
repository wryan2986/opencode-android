package dev.ryan.opencode.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the streaming speech path.
 *
 * The behaviour that makes voice output feel responsive is subtle — sentences must
 * be released the moment they complete, without re-speaking text on a resync, and
 * without turning code and URLs into noise. Each of those was a real defect class.
 */
class SpeechScriptTest {

    // ---- SentenceAccumulator: the latency-critical part ----

    @Test
    fun `emits a sentence as soon as it is complete, without waiting for the turn`() {
        val acc = SpeechScript.SentenceAccumulator()
        assertTrue("nothing speakable yet", acc.accept("I checked the ").isEmpty())
        // The terminator plus following space is enough to release it — this is
        // the whole point: do not wait for the model to finish the turn.
        assertEquals(listOf("I checked the logs."), acc.accept("I checked the logs. "))
        assertTrue("rest is mid-sentence", acc.accept("I checked the logs. Fix").isEmpty())
    }

    @Test
    fun `never re-speaks a prefix after a resync rewinds the buffer`() {
        val acc = SpeechScript.SentenceAccumulator()
        acc.accept("First sentence. ")
        // A resync can deliver a shorter/rewound cumulative string.
        val out = acc.accept("First")
        assertTrue("must not repeat already-spoken text", out.isEmpty())
    }

    @Test
    fun `is idempotent when the same cumulative text is delivered twice`() {
        val acc = SpeechScript.SentenceAccumulator()
        acc.accept("Hello there.")
        assertTrue("replaying identical text must be silent", acc.accept("Hello there.").isEmpty())
    }

    @Test
    fun `flush returns the trailing fragment the turn ended on`() {
        val acc = SpeechScript.SentenceAccumulator()
        acc.accept("All done. No trailing punctuation")
        assertEquals("No trailing punctuation", acc.flush())
        assertEquals("flush is not repeatable", null, acc.flush())
    }

    @Test
    fun `reset drops buffered text`() {
        val acc = SpeechScript.SentenceAccumulator()
        acc.accept("half a sentence")
        acc.reset()
        assertEquals(null, acc.flush())
    }

    @Test
    fun `splits multiple sentences arriving in one delta`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("One. Two. Three. Four")
        assertEquals(listOf("One.", "Two.", "Three."), out)
        assertEquals("Four", acc.flush())
    }

    // ---- sentence boundary detection ----

    @Test
    fun `decimal numbers do not end a sentence`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("The value is 3.14 exactly.")
        assertEquals(listOf("The value is 3.14 exactly."), out)
    }

    @Test
    fun `filenames and urls do not split mid-token`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("See config.json for details.")
        assertEquals(listOf("See config.json for details."), out)
    }

    @Test
    fun `abbreviations and initials do not split`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("Ask J. Smith about it.")
        assertEquals(listOf("Ask J. Smith about it."), out)
    }

    @Test
    fun `ellipsis is treated as one sentence`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("Hmm... I am not sure yet.")
        assertEquals(1, out.size)
        assertEquals("Hmm... I am not sure yet.", out.first())
    }

    @Test
    fun `question and exclamation terminate a sentence`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("Really? Yes! Done.")
        assertEquals(listOf("Really?", "Yes!", "Done."), out)
    }

    @Test
    fun `newline ends a sentence`() {
        val acc = SpeechScript.SentenceAccumulator()
        val out = acc.accept("First line\nSecond line")
        assertEquals(listOf("First line"), out)
    }

    // ---- markdown -> speech ----

    @Test
    fun `code blocks are summarised rather than read aloud`() {
        val speech = SpeechScript.toSpeech(
            "Here is the fix:\n```kotlin\nfun main() { println(\"hi\") }\n```\nThat is all."
        )
        assertTrue("must not speak code", !speech.contains("println"))
        assertTrue("must mention the omission: $speech", speech.contains("1 code block omitted"))
        assertTrue(speech.contains("Here is the fix"))
    }

    @Test
    fun `multiple code blocks are counted`() {
        val speech = SpeechScript.toSpeech("```a\nx\n```\ntext\n```b\ny\n```")
        assertTrue(speech.contains("2 code blocks omitted"))
    }

    @Test
    fun `inline code keeps the identifier`() {
        assertEquals(
            "Call resolve to continue.",
            SpeechScript.toSpeech("Call `resolve` to continue."),
        )
    }

    @Test
    fun `headings emphasis and list bullets are stripped`() {
        assertEquals(
            "Deploy steps",
            SpeechScript.toSpeech("## Deploy steps"),
        )
        assertEquals(
            "Important warning here",
            SpeechScript.toSpeech("**Important** _warning_ here"),
        )
        assertEquals(
            "Run the tests",
            SpeechScript.toSpeech("- Run the tests"),
        )
    }

    @Test
    fun `links speak the label not the url`() {
        val speech = SpeechScript.toSpeech("See [the docs](https://example.com/page) first.")
        assertTrue(speech.contains("the docs"))
        assertTrue("url must not be spoken: $speech", !speech.contains("example.com"))
    }

    @Test
    fun `bare urls become the word link`() {
        assertEquals(
            "Open link for details",
            SpeechScript.toSpeech("Open https://example.com for details"),
        )
    }

    @Test
    fun `a code fence alone yields nothing speakable`() {
        assertEquals("", SpeechScript.toSpeech("```\nfoo()\n```"))
    }

    @Test
    fun `empty and whitespace input yields nothing`() {
        assertEquals("", SpeechScript.toSpeech(""))
        assertEquals("", SpeechScript.toSpeech("   \n  "))
    }
}
