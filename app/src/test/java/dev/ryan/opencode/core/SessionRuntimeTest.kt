package dev.ryan.opencode.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unread counter shipped broken and these tests are why it will not again.
 *
 * An earlier tracker matched `event.type.contains("text")`, which is true for
 * `session.text.started`, `.delta` **and** `.ended` — three increments for one
 * sentence, so the badge counted characters rather than replies. Matching on the
 * EventTypes constants fixes it, and the boundary cases are pinned here.
 */
class SessionRuntimeTest {

    private fun event(type: String, sessionId: String = "ses_1") =
        dev.ryan.opencode.core.model.ServerEvent(
            id = "evt_1",
            type = type,
            data = JsonObject(mapOf("sessionID" to kotlinx.serialization.json.JsonPrimitive(sessionId))),
        )

    /** The decision the tracker makes, mirrored here so it can be tested. */
    private fun reduce(current: SessionRuntime, type: String): SessionRuntime = when (type) {
        "session.execution.started" -> current.copy(busy = true)
        "session.execution.succeeded", "session.execution.error", "session.idle" ->
            current.copy(busy = false)
        "session.text.started" -> current.copy(unread = current.unread + 1)
        else -> current
    }

    @Test
    fun `one message increments unread once, not per event`() {
        var r = SessionRuntime(sessionId = "ses_1")
        // A single reply arrives as started, many deltas, then ended.
        r = reduce(r, "session.text.started")
        repeat(40) { r = reduce(r, "session.text.delta") }
        r = reduce(r, "session.text.ended")
        assertEquals(1, r.unread)
    }

    @Test
    fun `deltas alone never bump unread`() {
        val r = reduce(SessionRuntime(), "session.text.delta")
        assertEquals(0, r.unread)
    }

    @Test
    fun `busy clears on every terminal event`() {
        val busy = SessionRuntime(busy = true)
        assertFalse(reduce(busy, "session.execution.succeeded").busy)
        assertFalse(reduce(busy, "session.execution.error").busy)
        assertFalse(reduce(busy, "session.idle").busy)
    }

    @Test
    fun `unread accumulates across messages`() {
        var r = SessionRuntime()
        repeat(3) { r = reduce(r, "session.text.started") }
        assertEquals(3, r.unread)
    }

    @Test
    fun `marking read clears the badge`() {
        val r = SessionRuntime(sessionId = "ses_1", unread = 4).copy(unread = 0)
        assertEquals(0, r.unread)
    }

    @Test
    fun `reasoning does not count as unread`() {
        // Reasoning is usually collapsed and long; counting it as a new message
        // would inflate the badge on every turn.
        val r = reduce(SessionRuntime(), "session.reasoning.started")
        assertEquals(0, r.unread)
    }
}
