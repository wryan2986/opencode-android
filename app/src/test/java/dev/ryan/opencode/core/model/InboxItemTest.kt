package dev.ryan.opencode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxItemTest {

    @Test
    fun `decodes a steered item`() {
        val json = """{"id":"msg_1","sessionID":"ses_1","time":{"created":1},"type":"user",""" +
            """"payload":{"text":"actually, do this instead"},"delivery":"steer"}"""
        val item = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(InboxItem.serializer(), json)
        assertEquals("steer", item.delivery)
        assertTrue(item.isSteer)
        assertEquals("actually, do this instead", item.text)
    }

    /**
     * `delivery` defaults to `queue`. That default matters: an unrecognised value
     * must not silently be treated as a steer, because the user would then be told
     * their message landed in the running turn when it is actually waiting.
     */
    @Test
    fun `an unknown delivery is not treated as a steer`() {
        val item = InboxItem(delivery = "something-new")
        assertFalse(item.isSteer)
    }

    @Test
    fun `summary collapses whitespace for the queue row`() {
        val item = InboxItem(payload = InboxPayload(text = "  fix   the\n\tbroken  test  "))
        assertEquals("fix the broken test", item.summary)
    }

    @Test
    fun `summary truncates long instructions`() {
        val item = InboxItem(payload = InboxPayload(text = "x".repeat(400)))
        assertEquals(90, item.summary.length)
    }

    @Test
    fun `non-user inbox kinds decode without a payload of their own`() {
        val json = """{"id":"msg_2","sessionID":"ses_1","time":{"created":2},"type":"compaction"}"""
        val item = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(InboxItem.serializer(), json)
        assertEquals("compaction", item.type)
        assertFalse(item.isSteer)
        assertEquals("", item.summary)
    }
}