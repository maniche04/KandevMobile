package ca.mslab.forge.data

import ca.mslab.forge.data.sync.activityLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateHostTest {
    @Test fun plainHttpOnlyForTailscaleAndLocalHosts() {
        listOf("100.101.5.9", "192.168.1.10", "10.0.2.2", "172.20.1.1", "localhost", "devbox", "forge.tail1234.ts.net",
            "nas.local", "fd7a:115c:a1e0::1").forEach { assertTrue(it, KandevApi.isPrivateHost(it)) }
        listOf("8.8.8.8", "100.200.1.1", "forge.example.com", "172.32.0.1").forEach { assertFalse(it, KandevApi.isPrivateHost(it)) }
        assertThrows(IllegalArgumentException::class.java) { KandevApi.validateServerUrl("http://forge.example.com") }
        KandevApi.validateServerUrl("http://100.64.0.7:38429")
        KandevApi.validateServerUrl("https://forge.example.com")
    }

    @Test fun activityLinesAreShortAndSpecific() {
        val shell = ChatMessage("m", "s", "agent", "git status", "tool_execute", "2026-10-30T00:00:00Z",
            metadata = MessageMetadata(title = "git status --short"))
        assertEquals("Running git status --short", activityLine(shell))
        val text = ChatMessage("m", "s", "agent", "\nAll tests pass.\nDetails follow", "message", "2026-10-30T00:00:00Z")
        assertEquals("All tests pass.", activityLine(text))
    }
}
