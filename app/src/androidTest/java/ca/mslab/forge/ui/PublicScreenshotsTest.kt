package ca.mslab.forge.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ca.mslab.forge.data.*
import ca.mslab.forge.data.db.StepRow
import ca.mslab.forge.data.db.WorkflowRow
import ca.mslab.forge.ui.theme.ForgeTheme
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Captures real screens with synthetic state only. Never connects to a server. */
class PublicScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    // Keep fixture injection out of production APIs. These fields are Compose MutableState delegates.
    @Suppress("UNCHECKED_CAST")
    private fun set(model: ConnectionViewModel, name: String, value: Any?) {
        val field = ConnectionViewModel::class.java.getDeclaredField(name + "\$delegate")
        field.isAccessible = true
        (field.get(model) as MutableState<Any?>).value = value
    }

    @Test fun capturePublicScreens() {
        assumeTrue("Public screenshots require explicit opt-in",
            InstrumentationRegistry.getArguments().getString("forgeScreenshots") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val hub = ca.mslab.forge.data.sync.ForgeHub.get(app)
        assertNull("Use a fresh debug app with no saved server profile for public screenshots", kotlinx.coroutines.runBlocking { hub.vault.read() })
        lateinit var model: ConnectionViewModel
        rule.runOnIdle { model = ConnectionViewModel(app) }
        rule.waitUntil(5_000) { !model.restoring }
        val workspace = Workspace("demo-workspace", "Studio", "Example projects")
        val now = java.time.Instant.now().toString()
        val tasks = listOf(
            TaskSummary("demo-search", "Add search to the library", "IN_PROGRESS", primarySessionId = "demo-session",
                agentName = "Codex", sessionState = "RUNNING", activity = "generating", stepId = "working",
                workflowId = "demo-flow", workspaceId = workspace.id, updatedAt = now,
                repositories = listOf(TaskRepository("demo-repo", "feature/search")),
                summary = TaskStatusSummary(running = true, git = TaskGitSummary(86, 12, 4))),
            TaskSummary("demo-approval", "Review the database migration", "IN_PROGRESS", agentName = "Claude",
                sessionState = "WAITING_FOR_INPUT", sessionPendingAction = "permission", stepId = "working", updatedAt = now),
            TaskSummary("demo-docs", "Write the getting started guide", "IN_PROGRESS", agentName = "Claude",
                sessionState = "RUNNING", stepId = "working", updatedAt = now),
            TaskSummary("demo-icons", "Polish the settings screen", "TODO", stepId = "planned", updatedAt = now),
            TaskSummary("demo-tests", "Cover offline draft recovery", "TODO", stepId = "planned", updatedAt = now),
            TaskSummary("demo-done", "Add keyboard shortcuts", "COMPLETED", stepId = "done", updatedAt = now)
        )
        rule.runOnIdle {
            set(model, "workspace", workspace)
            set(model, "snapshot", ConnectionSnapshot(SystemInfo(TARGET_KANDEV_VERSION, "demo"), listOf(workspace)))
            set(model, "tasks", tasks)
            set(model, "repositories", mapOf("demo-repo" to Repository("demo-repo", "library-app")))
            set(model, "steps", listOf(
                StepRow("working", workspace.id, "demo-flow", "In progress", 1, "#D97757"),
                StepRow("planned", workspace.id, "demo-flow", "Planned", 2, "#7B8CDE"),
                StepRow("done", workspace.id, "demo-flow", "Done", 3, "#61A27E", true)))
            set(model, "workflows", listOf(WorkflowRow("demo-flow", workspace.id, "Development", 0)))
            model.chooseHomeView(HomeView.Steps)
        }
        var chat by mutableStateOf(false)
        rule.setContent {
            ForgeTheme(darkTheme = chat) {
                val markdown = MarkdownRenderer(app)
                CompositionLocalProvider(LocalMarkdown provides markdown) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (chat) TaskConversationScreen(model)
                        else TaskListScreen(model, rememberLazyListState(), {})
                    }
                }
            }
        }
        save(app, "tasks-light")
        rule.runOnIdle {
            set(model, "task", tasks.first())
            val session = AgentSession("demo-session", state = "COMPLETED", agentProfileId = "demo-codex", baseBranch = "feature/search")
            set(model, "session", session)
            set(model, "sessions", listOf(session))
            set(model, "messages", listOf(
                ChatMessage("demo-1", session.id, "user", "Add search to the library. Match titles and authors, and keep it fast as I type.", createdAt = now),
                ChatMessage("demo-2", session.id, "agent", "I'll add a search field, filter the local list, and cover empty results.", createdAt = now),
                ChatMessage("demo-3", session.id, "agent", "Updated 4 files", type = "tool_execute", createdAt = now,
                    metadata = MessageMetadata(title = "Update search and results", status = "complete")),
                ChatMessage("demo-4", session.id, "agent", "## Search is ready\n\nThe library now matches **titles and authors** as you type.\n\n- Search stays on device\n- Empty results offer a clear reset\n- Your current query survives rotation\n\nAll 12 search tests pass. Ready for your review.", createdAt = now)
            ))
            chat = true
        }
        save(app, "chat-dark")
    }

    private fun save(app: Application, name: String) {
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
