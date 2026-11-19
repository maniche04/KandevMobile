package ca.mslab.forge.data

import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDownloadTest {
    @Test fun `binary downloads preserve every byte including non UTF-8 content`() {
        val bytes = ByteArray(256) { it.toByte() }
        val file = WorkspaceFile("report.pdf", Base64.getMimeEncoder().encodeToString(bytes), binary = true)
        assertArrayEquals(bytes, fileBytes(file))
        assertArrayEquals(byteArrayOf(), fileBytes(file.copy(content = "")))
    }

    @Test fun `text downloads use UTF-8 without interpreting base64-looking text`() {
        val text = "aGVsbG8=\nRésumé नमस्ते\r\n"
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), fileBytes(WorkspaceFile("notes.txt", text)))
    }

    @Test fun `browser download uses session and path without changing navigation`() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val model = WorkspaceController(this, { action, payload ->
            requests += action to payload
            buildJsonObject { put("path", "docs/report.pdf"); put("content", "AAEC/w=="); put("is_binary", true) }
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        val file = model.fileForDownload("docs/report.pdf")
        assertEquals("workspace.file.get", requests.single().first)
        assertEquals("s", requests.single().second["session_id"]?.jsonPrimitive?.content)
        assertEquals("docs/report.pdf", requests.single().second["path"]?.jsonPrimitive?.content)
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), fileBytes(file))
        assertNull(model.file)
        assertEquals(WorkspaceTab.Chat, model.tab)
        assertEquals("", model.directory)
    }

    @Test fun `viewer download remains available offline and excludes unsaved edits`() = runTest {
        var reads = 0
        val model = WorkspaceController(this, { _, _ ->
            reads++
            buildJsonObject { put("path", "notes.txt"); put("content", "saved") }
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.openFile("notes.txt"); advanceUntilIdle()
        model.editFile(); model.updateFileDraft("unsaved"); advanceUntilIdle()
        model.onDisconnected()
        assertEquals("saved", model.fileForDownload("notes.txt").content)
        assertEquals(1, reads)
        assertEquals("unsaved", model.fileDraft)
        assertTrue(runCatching { model.fileForDownload("missing.txt") }.isFailure)
    }

    @Test fun `server file errors are rejected before saving`() = runTest {
        val model = WorkspaceController(this, { _, _ ->
            buildJsonObject { put("path", "missing"); put("content", ""); put("error", "Not found") }
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        assertTrue(runCatching { model.fileForDownload("missing") }.isFailure)
    }
}
