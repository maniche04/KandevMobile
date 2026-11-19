package ca.mslab.forge.data

import ca.mslab.forge.data.sync.TodoItem
import ca.mslab.forge.notify.todoLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLinkTest {
    @Test fun recognisesOpenableFiles() {
        listOf("app/src/main/Foo.kt", "/tmp/c1.png", "README.md", "./gradlew.bat", "docs/UX_REVIEW.md").forEach { assertTrue(it, isOpenableFilePath(it)) }
        listOf("app/src/", ".", "https://x.y/a.png", "hello world.txt", "notes", "v0.98.0").forEach { assertFalse(it, isOpenableFilePath(it)) }
        assertTrue(isImagePath("shot.PNG")); assertFalse(isImagePath("Foo.kt"))
    }

    @Test fun absolutePathsInsideTheWorkspaceBecomeRelative() {
        assertEquals("app/Foo.kt", workspaceRelative("/home/me/repo/app/Foo.kt", "/home/me/repo"))
        assertEquals("app/Foo.kt", workspaceRelative("./app/Foo.kt", "/home/me/repo"))
        assertEquals("/tmp/c1.png", workspaceRelative("/tmp/c1.png", "/home/me/repo"))
    }

    @Test fun markdownWorkspaceDestinationsIncludeFoldersAndExtensionlessFiles() {
        assertEquals("output/icon-concepts/v2", workspaceLinkPath("/home/me/repo/output/icon-concepts/v2")?.let { workspaceRelative(it, "/home/me/repo") })
        assertEquals("docs/My Folder/", workspaceLinkPath("docs/My%20Folder/"))
        assertEquals("src/Foo.kt", workspaceLinkPath("src/Foo.kt:12:3"))
        assertEquals("README.md", workspaceLinkPath("README.md:12"))
        assertEquals("Makefile", workspaceLinkPath("Makefile"))
        assertEquals("a+b.txt", workspaceLinkPath("a+b.txt"))
        assertEquals("", workspaceRelative("/home/me/repo", "/home/me/repo"))
        listOf("https://example.com/file.kt", "mailto:me@example.com", "content://files/1", "#heading", "//example.com/path").forEach {
            org.junit.Assert.assertNull(it, workspaceLinkPath(it))
        }
    }

    @Test fun toolCallsExposeTheFileTheyTouched() {
        val read = ChatMessage("m", "s", "agent", "", "tool_read", "2026-10-30T00:00:00Z",
            metadata = MessageMetadata(normalized = NormalizedTool(readFile = FileRef("/tmp/c1.png"))))
        assertEquals("/tmp/c1.png", read.toolFilePath)
    }

    @Test fun notificationTodosLeadWithTheCurrentStep() {
        val todos = listOf(TodoItem("Plan", "completed"), TodoItem("Build", "in_progress"), TodoItem("Ship", "pending"))
        assertEquals(listOf("▶ Build", "○ Ship", "✓ Plan"), todoLines(todos))
    }
}
