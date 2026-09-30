package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.Session
import dev.ryan.opencode.core.model.SessionPage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a **real** 50-session response captured from the live server.
 *
 * A synthetic two-element fixture passed while the real payload silently yielded
 * an empty list, because a single undecodable element aborts the whole array
 * decode and our fallback then returns empty. This test exists to make that class
 * of failure loud.
 */
class SessionsFixtureTest {

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/sessions.json")) {
            "fixtures/sessions.json missing from test resources"
        }.bufferedReader().readText()

    @Test
    fun `real 50-session payload decodes without collapsing to empty`() {
        val raw = fixture()
        val decoded = runCatching { OpencodeJson.decodeFromString<SessionPage>(raw).data }
            .recoverCatching { OpencodeJson.decodeFromString<List<Session>>(raw) }

        val result = decoded.getOrElse { e ->
            throw AssertionError(
                "REAL payload failed to decode (${e::class.simpleName}: ${e.message}). " +
                    "A single bad element silently empties the session list in the UI.",
                e,
            )
        }

        assertTrue("expected 50 sessions but got ${result.size}", result.size >= 40)
        assertTrue(result.any { it.title != null })
    }

    /** Narrow it down: decode element-by-element so the offending shape is obvious. */
    @Test
    fun `each session element decodes independently`() {
        val raw = fixture()
        val envelope = OpencodeJson.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val elements = envelope["data"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
        val failures = mutableListOf<String>()
        elements.forEachIndexed { index, element ->
            runCatching { OpencodeJson.decodeFromString<Session>(element.toString()) }
                .onFailure { failures += "[$index] ${it.message} :: ${element.toString().take(200)}" }
        }
        assertTrue(
            "elements that failed to decode:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
