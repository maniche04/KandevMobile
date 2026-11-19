package ca.mslab.forge.data

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProtocolTest {
    @Test fun `whole-file patches apply with upstream p0 for empty newline and unicode files`() {
        val root = Files.createTempDirectory("forge-patch-test").toFile()
        try {
            ProcessBuilder("git", "init", "-q", root.path).start().also { assertEquals(0,it.waitFor()) }
            for ((original, desired) in listOf("" to "new\n", "old\n" to "new\n", "old" to "new", "old\n" to "",
                "a\r\nb\r\n" to "c\r\n", "α\n" to "日本語\n", "" to "no newline")) {
                root.resolve("a file.txt").writeText(original)
                root.resolve("update.patch").writeText(replacementPatch("a file.txt", original, desired))
                val process = ProcessBuilder("git", "apply", "-p0", "--unidiff-zero", "--whitespace=nowarn", "update.patch")
                    .directory(root).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.waitFor())
                assertEquals(desired, root.resolve("a file.txt").readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `file saves use original hash and never enable upstream overwrite fallback`() = runBlocking {
        var file = "original\n"
        val requests = mutableListOf<Pair<String,JsonObject>>()
        val model = WorkspaceController(this, { action, payload ->
            requests += action to payload
            when (action) {
                "workspace.tree.get" -> Json.parseToJsonElement("""{"root":{"name":"root","path":"","is_dir":true,"children":[]}}""").jsonObject
                "workspace.file.get" -> buildJsonObject { put("path","a.txt"); put("content",file) }
                "workspace.file.update" -> {
                    assertEquals(digest(file), payload["original_hash"]?.jsonPrimitive?.content)
                    assertFalse(payload.containsKey("desired_content"))
                    file="edited\n"; buildJsonObject { put("success",true) }
                }
                else -> buildJsonObject { }
            }
        }, {null}, {null})
        model.selectSession("task", AgentSession("s",state="IDLE")); model.onConnected()
        model.openFile("a.txt"); awaitIdle(model)
        model.editFile(); model.updateFileDraft("edited\n"); model.saveFile()
        withTimeout(5000) { while (model.fileSaving) delay(5) }
        assertEquals("edited\n", decodeFile(requireNotNull(model.file)))
        assertNull(model.fileDraft)
        assertTrue(requests.any { it.first=="workspace.file.update" })
    }

    @Test fun `file conflict keeps the edit and does not retry automatically`() = runBlocking {
        var writes=0
        val model=WorkspaceController(this,{action,_ ->
            if(action=="workspace.file.get") buildJsonObject { put("path","a.txt");put("content","old\n") }
            else { writes++;throw KandevApiException("conflict") }
        },{null},{null})
        model.selectSession("t",AgentSession("s",state="IDLE"));model.onConnected()
        model.openFile("a.txt");awaitIdle(model)
        model.editFile();model.updateFileDraft("keep me");model.saveFile()
        withTimeout(5000) { while(model.fileSaving)delay(5) }
        assertEquals("keep me",model.fileDraft);assertEquals(1,writes);assertNotNull(model.error)
    }

    @Test fun `unavailable live git refresh still reads cumulative changes`() = runBlocking {
        val model = WorkspaceController(this, { action, _ ->
            when (action) {
                "session.git.refresh" -> throw KandevApiException("unavailable")
                "session.cumulative_diff" -> Json.parseToJsonElement("""{"cumulative_diff":null}""").jsonObject
                else -> buildJsonObject { put("success", true) }
            }
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.selectTab(WorkspaceTab.Changes); awaitIdle(model)
        assertNull(model.error)
    }

    @Test fun `metadata-only git events preserve file membership`() = runBlocking {
        val model = WorkspaceController(this, { _, _ -> buildJsonObject {} }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE"))
        fun event(status: String) = WireMessage(type = "notification", action = "session.git.event",
            payload = Json.parseToJsonElement("""{"session_id":"s","type":"status_update","status":$status}""").jsonObject)
        model.onEvent(event("""{"branch":"main","status_state":"ready","modified":["a.txt"]}"""))
        model.onEvent(event("""{"branch":"main","head_commit":"new-head"}"""))
        assertEquals(setOf("a.txt"), model.changes.statuses.getValue("").files.keys)
        model.onEvent(event("""{"modified":[],"added":[],"deleted":[],"untracked":[],"renamed":[]}"""))
        assertTrue(model.changes.statuses.getValue("").files.isEmpty())
    }

    @Test fun `offline editor restores unsaved content with its original conflict hash`() = runBlocking {
        val root = Files.createTempDirectory("forge-editor-test").toFile()
        val child = CoroutineScope(coroutineContext + SupervisorJob())
        val store = SnapshotStore(root, "profile")
        try {
            val original = WorkspaceFile("a.txt", "original\n")
            store.write("file:s:a.txt", WorkspaceFile.serializer(), original.copy(content = "new server content"))
            store.write("edit:s:a.txt", FileEditSnapshot.serializer(), FileEditSnapshot(original, "local edit"))
            val model = WorkspaceController(child, { _, _ -> error("Offline must not make panel requests") }, { store }, { null })
            model.selectSession("t", AgentSession("s", state = "IDLE")); model.openFile("a.txt"); awaitIdle(model)
            assertEquals("local edit", model.fileDraft)
            assertEquals("original\n", decodeFile(requireNotNull(model.file)))
            assertFalse(model.connected)
            model.cancelFileEdit(); delay(50)
            assertNull(store.read("edit:s:a.txt", FileEditSnapshot.serializer()))
        } finally { child.cancel(); root.deleteRecursively() }
    }

    @Test fun `terminal ring preserves newest bytes across wraparound`() {
        val ring=ByteRing(5);ring.append("abc".toByteArray());ring.append("def".toByteArray())
        assertEquals("bcdef",ring.snapshot().toString(Charsets.UTF_8))
        ring.append("123456789".toByteArray());assertEquals("56789",ring.snapshot().toString(Charsets.UTF_8))
        ring.clear();assertTrue(ring.snapshot().isEmpty())
    }
    @Test fun `PTY resize frame has the exact binary header and bounded dimensions`() {
        val bytes=terminalResize(80,24).toByteArray()
        assertEquals(1,bytes[0].toInt())
        assertEquals("{\"cols\":80,\"rows\":24}",bytes.copyOfRange(1,bytes.size).toString(Charsets.UTF_8))
        assertTrue(runCatching { terminalResize(0,24) }.isFailure)
    }
    @Test fun `tool grouping preserves user boundaries turn identity and input requests`() {
        fun message(id:String,type:String,turn:String="t",input:Boolean=false)=ChatMessage(id,"s","agent",id,type,"2026-10-30T00:00:00Z",requestsInput=input,turnId=turn)
        val groups=conversationGroups(listOf(message("1","tool_read"),message("2","tool_execute"),message("3","message"),
            message("4","tool_call","other"),message("5","tool_call","other",true)))
        assertEquals(listOf(2,1,1,1),groups.map { it.messages.size })
    }
    private suspend fun awaitIdle(model:WorkspaceController) { withTimeout(5000){while(model.busy)delay(5)} }
}
