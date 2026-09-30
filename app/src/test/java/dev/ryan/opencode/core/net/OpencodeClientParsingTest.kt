package dev.ryan.opencode.core.net

import dev.ryan.opencode.core.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * Regression tests against **real captured responses** from opencode 2.0.20.
 *
 * The response envelope is inconsistent across endpoints (some bare, some
 * `{data: ...}`), which is exactly the kind of thing that silently yields an
 * empty list in the UI. These fixtures pin the actual wire format.
 */
class OpencodeClientParsingTest {

    /** Captured from GET /api/session?location[directory]=/home/ryan */
    private val sessionsJson = """
    {"data":[
      {"id":"ses_f0bf99a11ffege3QTes5jUByCu","projectID":"6e654ea5aa5b0ca41650f04e1610717fd67bac45",
       "cost":0,"tokens":{"input":8505,"output":35,"reasoning":259,"cache":{"read":7168,"write":0}},
       "outcome":"succeeded",
       "time":{"created":1790800455156,"updated":1790800461149,"idle":1790800468381},
       "title":"List files in current directory","location":{"directory":"/home/ryan"}},
      {"id":"ses_f0bf9b513ffeT1rMTMT4CEMJe7","projectID":"6e654ea5aa5b0ca41650f04e1610717fd67bac45",
       "cost":0,"tokens":{"input":86,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
       "time":{"created":1790800448302,"updated":1790800448302},
       "title":"Second session","location":{"directory":"/home/ryan"}}
    ]}
    """.trimIndent()

    @Test
    fun `sessions response parses from the data envelope`() {
        val parsed = runCatching { OpencodeJson.decodeFromString<List<Session>>(sessionsJson) }
            .recoverCatching {
                OpencodeJson.decodeFromString<Map<String, List<Session>>>(sessionsJson)["data"].orEmpty()
            }
            .getOrElse { e -> throw AssertionError("session parsing failed: ${e.message}", e) }

        assertEquals("should parse 2 sessions, got ${parsed.size}", 2, parsed.size)
        assertEquals("ses_f0bf99a11ffege3QTes5jUByCu", parsed[0].id)
        assertEquals("List files in current directory", parsed[0].title)
        assertEquals(8505L, parsed[0].tokens.input)
        assertEquals("/home/ryan", parsed[0].location?.directory)
    }

    /** Captured from POST /api/session — a single object under `data`. */
    @Test
    fun `single session parses from the data envelope`() {
        val json = """{"data":{"id":"ses_x","projectID":"p1","cost":0,
            "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
            "time":{"created":1,"updated":2},"location":{"directory":"/home/ryan"}}}"""
        val session = OpencodeJson.decodeFromString<Map<String, Session>>(json)["data"]
        assertEquals("ses_x", session?.id)
    }

    /** The prompt response nests the user text under `payload`, not at the top level. */
    @Test
    fun `prompt response exposes payload text`() {
        val json = """{"data":{"id":"msg_1","sessionID":"ses_x","time":{"created":1},
            "type":"user","payload":{"text":"hello"},"delivery":"steer"}}"""
        val message = OpencodeJson.decodeFromString<Map<String, dev.ryan.opencode.core.model.Message>>(json)["data"]
        assertEquals("msg_1", message?.id)
        assertTrue("user message should be typed", message!!.isUser)
    }

    /** GET /api/project returns a BARE array — not enveloped. */
    @Test
    fun `projects parse from a bare array`() {
        val json = """[{"id":"p1","canonical":"/home/ryan",
            "time":{"created":1,"updated":2,"active":3},"sandboxes":[]}]"""
        val projects = OpencodeJson.decodeFromString<List<dev.ryan.opencode.core.model.Project>>(json)
        assertEquals(1, projects.size)
        assertEquals("ryan", projects[0].name)
    }

    /** Newest-first ordering matters: the chat UI reverses it. */
    @Test
    fun `message page is newest first and must be reversed for display`() {
        val json = """{"data":[
            {"id":"msg_3","time":{"created":3},"type":"idle","outcome":"succeeded"},
            {"id":"msg_2","time":{"created":2,"completed":2},"type":"assistant",
             "content":[{"type":"text","text":"DONE"}],"finish":"stop"},
            {"id":"msg_1","time":{"created":1},"type":"user","text":"hi"}
        ],"cursor":{"previous":null,"next":null}}"""
        val page = OpencodeJson.decodeFromString<dev.ryan.opencode.core.model.MessagePage>(json)
        assertEquals(listOf("msg_3", "msg_2", "msg_1"), page.data.map { it.id })
        assertEquals(listOf("msg_1", "msg_2", "msg_3"), page.data.asReversed().map { it.id })
    }

    @Test
    fun `assistant message splits reasoning from visible text`() {
        val json = """{"id":"msg_2","time":{"created":2,"completed":2},"type":"assistant",
            "agent":"build","model":{"id":"m1","providerID":"p1"},
            "content":[{"type":"reasoning","text":"thinking"},
                       {"type":"text","text":"DONE"}],
            "finish":"stop","cost":0,
            "tokens":{"input":10,"output":2,"reasoning":5,"cache":{"read":0,"write":0}}}"""
        val m = OpencodeJson.decodeFromString<dev.ryan.opencode.core.model.Message>(json)
        assertTrue(m.isAssistant)
        assertEquals("DONE", m.textContent)
        assertEquals("thinking", m.reasoning)
        assertTrue(m.completed)
    }

    /** The session cookie name embeds the port — verified against the server. */
    @Test
    fun `session cookie name follows the port`() {
        assertEquals(
            "opencode_session_4096",
            sessionCookieName("http://100.102.124.47:4096".toHttpUrl()),
        )
        assertEquals(
            "opencode_session",
            sessionCookieName("https://tunnel.example.com".toHttpUrl()),
        )
    }

    /** Host normalisation accepts the shapes a user will actually type. */
    @Test
    fun `host normalisation handles bare hosts and schemes`() {
        assertEquals(
            "http://100.102.124.47:4096/",
            DirectTransport.normalize("100.102.124.47:4096").toString(),
        )
        assertEquals(
            "http://100.102.124.47:4096/",
            DirectTransport.normalize("100.102.124.47").toString(),
        )
        assertEquals(
            "https://tunnel.trycloudflare.com/",
            DirectTransport.normalize("https://tunnel.trycloudflare.com").toString(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank host is rejected with a clear message`() {
        DirectTransport.normalize("   ")
    }
}
