package ca.mslab.forge.data

import ca.mslab.forge.data.sync.activityLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalFoldingTest {
    private fun tool(id: String, call: String) = ChatMessage(id, "s", "agent", "git status", "tool_execute", "2026-10-30T00:00:0${id.last()}Z",
        metadata = MessageMetadata(title = "git status", toolCallId = call))
    private fun permission(id: String, call: String, status: String, requests: Boolean = false, source: String? = "auto_approve") =
        ChatMessage(id, "s", "agent", "git status", "permission_request", "2026-10-30T00:00:0${id.last()}Z", requestsInput = requests,
            metadata = MessageMetadata(status = status, toolCallId = call,
                resolution = source?.let { PermissionResolution(source = it, actorKind = "automation", result = "accepted") }))

    @Test fun autoApprovedRequestsFoldIntoTheirToolCall() {
        val messages = listOf(tool("t1", "c1"), permission("p2", "c1", "approved"))
        assertEquals(listOf("t1"), visibleMessages(messages).map { it.id })
        val approval = approvalsByToolCall(messages)["c1"]
        assertTrue(approval!!.isAutoApproved())
    }

    @Test fun openAndDeniedRequestsStayVisible() {
        val messages = listOf(tool("t1", "c1"), permission("p2", "c1", "pending", requests = true, source = null),
            tool("t3", "c3"), permission("p4", "c3", "rejected", source = null))
        assertEquals(listOf("t1", "p2", "t3", "p4"), visibleMessages(messages).map { it.id })
        assertTrue(approvalsByToolCall(messages).isEmpty() || approvalsByToolCall(messages).keys == setOf("c3"))
    }

    @Test fun settledApprovalsAreNotReportedAsWaiting() {
        assertEquals("Running git status", activityLine(permission("p1", "c1", "approved")))
        assertEquals("Waiting for approval", activityLine(permission("p1", "c1", "pending", requests = true, source = null)))
    }

    @Test fun autoApprovalsNoLongerSplitToolGroups() {
        val messages = listOf(tool("t1", "c1"), permission("p2", "c1", "approved"), tool("t3", "c3"), permission("p4", "c3", "approved"))
        val groups = conversationGroups(visibleMessages(messages))
        assertEquals(1, groups.size); assertEquals(2, groups.single().messages.size)
    }
}
