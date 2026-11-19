package ca.mslab.forge.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun message(content: String, updated: String = "2026-10-30T01:00:00Z") = ChatMessage(
        "m", "s", "agent", content, "content", "2026-10-30T00:00:00Z", updated,
    )
    private fun change(base: String, revision: String, message: ChatMessage? = null, kind: String = "upsert") =
        ConversationChange(2, "scope", "s", "epoch", base, revision,
            operations = listOf(ConversationOperation(kind, "message", "m", message?.let {
                json.encodeToJsonElement(ChatMessage.serializer(), it).jsonObject
            })))

    @Test fun `updates deduplicate by id and stale buffered content cannot replace a newer snapshot`() {
        val projection = ConversationProjection("s", "scope", "epoch", "10")
        val snapshot = listOf(message("new", "2026-10-30T01:01:00Z"))
        val result = projection.apply(change("10", "11", message("old")), snapshot)
        assertEquals(snapshot, result)
        assertEquals(result, projection.apply(change("10", "11", message("old")), result))
        val newer = message("streamed", "2026-10-30T01:02:00Z")
        assertEquals(listOf(newer), projection.apply(change("11", "12", newer), result))
    }

    @Test fun `remove operation removes a row`() {
        val projection = ConversationProjection("s", "scope", "epoch", "0")
        assertTrue(projection.apply(change("0", "1", kind = "remove"), listOf(message("body"))).isEmpty())
    }

    @Test fun `revision gaps epoch changes and reset invalidations require snapshot recovery`() {
        val projection = ConversationProjection("s", "scope", "epoch", "10")
        for (value in listOf(change("11", "12"), change("10", "11").copy(epoch = "other"),
            change("", "").copy(reset = true))) {
            assertTrue(runCatching { projection.apply(value, emptyList()) }.isFailure)
        }
    }

    @Test fun `same revision check is a heartbeat and a newer watermark requires recovery`() {
        val projection = ConversationProjection("s", "scope", "epoch", "100000000000000000000")
        val check = ConversationChange(2, "scope", "s", "epoch", "100000000000000000000", "100000000000000000000",
            check = true, operations = emptyList())
        assertEquals(emptyList<ChatMessage>(), projection.apply(check, emptyList()))
        assertTrue(runCatching { projection.apply(check.copy(revision = "100000000000000000001"), emptyList()) }.isFailure)
    }

    @Test fun `cross-session messages cannot enter a conversation`() {
        val projection = ConversationProjection("s", "scope", "epoch", "0")
        assertTrue(runCatching { projection.apply(change("0", "1", message("foreign").copy(sessionId = "other")), emptyList()) }.isFailure)
    }

    @Test fun `ordering uses instants with mixed fractional precision and id tie breaks`() {
        val rows = listOf(message("second").copy(id = "b", createdAt = "2026-10-30T00:00:00.1Z"),
            message("first").copy(id = "a", createdAt = "2026-10-30T00:00:00Z"))
        assertEquals(listOf("a", "b"), orderedMessages(rows).map { it.id })
    }
}
