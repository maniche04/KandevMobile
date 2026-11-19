package ca.mslab.forge.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceNavigationTest {
    private fun tree(path: String): JsonObject = Json.encodeToJsonElement(FileTree.serializer(), FileTree(
        FileNode(path.substringAfterLast('/'), path, true, children = when (path) {
            "" -> listOf(FileNode("output", "output", true), FileNode("Makefile", "Makefile", false))
            "output" -> listOf(FileNode("v2.0", "output/v2.0", true))
            else -> listOf(FileNode("icon.png", "$path/icon.png", false))
        })
    )).jsonObject

    @Test fun `returning through folders restores their items without another request even offline`() = runTest {
        val fetched = mutableListOf<String>()
        val model = WorkspaceController(this, { _, payload ->
            val path = payload.getValue("path").jsonPrimitive.content
            fetched += path; tree(path)
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.selectTab(WorkspaceTab.Files); advanceUntilIdle()
        model.enterDirectory("output"); advanceUntilIdle()
        model.enterDirectory("output/v2.0"); advanceUntilIdle()
        model.onDisconnected()
        model.back(); advanceUntilIdle()
        assertEquals("output", model.directory)
        assertEquals("v2.0", model.tree?.root?.children?.single()?.name)
        model.enterDirectory(""); advanceUntilIdle()
        assertEquals(listOf("output", "Makefile"), model.tree?.root?.children?.map { it.name })
        assertEquals(listOf("", "output", "output/v2.0"), fetched)
        assertFalse(model.busy)
    }

    @Test fun `chat links open dotted folders as folders and extensionless files as files`() = runTest {
        val fileRequests = mutableListOf<String>()
        val model = WorkspaceController(this, { action, payload ->
            val path = payload.getValue("path").jsonPrimitive.content
            if (action == "workspace.file.get") {
                fileRequests += path
                buildJsonObject { put("path", path); put("content", "all:") }
            } else tree(path)
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.openFromChat("output/v2.0"); advanceUntilIdle()
        assertEquals(WorkspaceTab.Files, model.tab)
        assertEquals("output/v2.0", model.directory)
        assertEquals("icon.png", model.tree?.root?.children?.single()?.name)
        assertTrue(fileRequests.isEmpty()); assertNull(model.file)
        model.openFile("output/v2.0/icon.png"); advanceUntilIdle()
        model.closeFile(); advanceUntilIdle()
        assertEquals(WorkspaceTab.Files, model.tab)
        assertEquals("output/v2.0", model.directory); assertNotNull(model.tree)
        model.openFromChat("Makefile"); advanceUntilIdle()
        assertEquals("Makefile", model.file?.path)
        model.closeFile(); advanceUntilIdle()
        assertEquals(WorkspaceTab.Chat, model.tab)
        model.openFromChat(""); advanceUntilIdle()
        assertEquals("", model.directory); assertNotNull(model.tree)
    }

    @Test fun `rapid folder navigation cannot publish a late response into another folder`() = runTest {
        val model = WorkspaceController(this, { _, payload ->
            val path = payload.getValue("path").jsonPrimitive.content
            if (path == "output") withContext(NonCancellable) { delay(100) }
            tree(path)
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.selectTab(WorkspaceTab.Files); advanceUntilIdle()
        model.enterDirectory("output"); runCurrent()
        model.enterDirectory("output/v2.0"); advanceUntilIdle()
        assertEquals("output/v2.0", model.directory)
        assertEquals("output/v2.0", model.tree?.root?.path)
        assertFalse(model.busy)
        model.enterDirectory("output"); advanceUntilIdle()
        assertEquals("output", model.tree?.root?.path)
    }

    @Test fun `closing a loading file cannot reopen it after returning to its cached folder`() = runTest {
        val model = WorkspaceController(this, { action, payload ->
            if (action == "workspace.file.get") {
                withContext(NonCancellable) { delay(100) }
                buildJsonObject { put("path", "Makefile"); put("content", "all:") }
            } else tree(payload.getValue("path").jsonPrimitive.content)
        }, { null }, { null })
        model.selectSession("t", AgentSession("s", state = "IDLE")); model.onConnected()
        model.selectTab(WorkspaceTab.Files); advanceUntilIdle()
        model.openFile("Makefile"); runCurrent()
        model.closeFile(); advanceUntilIdle()
        assertNull(model.file); assertNotNull(model.tree); assertFalse(model.busy)
    }
}
