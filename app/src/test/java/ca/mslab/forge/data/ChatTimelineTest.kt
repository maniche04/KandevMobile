package ca.mslab.forge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTimelineTest {
    private fun tool(id: String, second: Int, path: String? = null) = ChatMessage(id, "s", "agent", "Read", "tool_read",
        "2026-11-18T10:00:%02dZ".format(second), turnId = "t",
        metadata = MessageMetadata(normalized = path?.let { NormalizedTool(readFile = FileRef(it)) }))

    @Test fun imageReadsStandAloneOutsideToolGroups() {
        val messages = listOf(tool("1", 1), tool("2", 2, "/tmp/e21.png"), tool("3", 3), tool("4", 4, "src/Main.kt"))
        assertEquals(listOf(listOf("1"), listOf("2"), listOf("3", "4")), conversationGroups(messages).map { g -> g.messages.map { it.id } })
        assertEquals("/tmp/e21.png", messages[1].imageReadPath)
        assertNull(messages[3].imageReadPath)
    }

    @Test fun aSentPromptSplitsTheGroupAndKeepsItsPlace() {
        val messages = listOf(tool("1", 1), tool("2", 2), tool("3", 5), tool("4", 6))
        val sentAfter = "2026-11-18T10:00:02Z"
        val groups = conversationGroups(messages, listOf(sentAfter))
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), groups.map { g -> g.messages.map { it.id } })
        // The bubble goes after the first group: newer replies render below it, not above.
        assertEquals(0, outgoingAnchor(groups, sentAfter))
        assertEquals(-1, outgoingAnchor(groups, null))
        assertEquals(1, outgoingAnchor(groups, "2026-11-18T10:00:06Z"))
    }
}
