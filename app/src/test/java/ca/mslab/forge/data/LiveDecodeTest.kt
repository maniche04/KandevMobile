package ca.mslab.forge.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Decodes synthetic v0.98.0 protocol fixtures with the app's own models. */
class LiveDecodeTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private fun read(name: String) = requireNotNull(javaClass.classLoader!!.getResource(name)).readText()

    @Test fun messages() {
        val page = json.decodeFromString(MessagePage.serializer(), read("live-messages.json"))
        assertEquals(listOf("user", "agent", "agent"), page.messages.map { it.author })
        assertEquals("demo-call", page.messages[1].metadata?.toolCallId)
        assertFalse(page.hasMore)
    }
    @Test fun turns() {
        val turn = json.decodeFromString(TurnList.serializer(), read("live-turns.json")).turns.single()
        assertEquals("demo-session", turn.sessionId)
        assertEquals("2026-01-01T10:00:03Z", turn.completedAt)
    }
    @Test fun sessions() {
        val page = json.decodeFromString(SessionList.serializer(), read("live-sessions.json"))
        assertEquals(1, page.total)
        assertEquals("RUNNING", page.sessions.single().state)
        assertEquals("/srv/projects/example", page.sessions.single().workspacePath)
    }
}
