package ca.mslab.forge.ui

import ca.mslab.forge.data.Repository
import ca.mslab.forge.data.TaskError
import ca.mslab.forge.data.TaskRepository
import ca.mslab.forge.data.TaskStatusSummary
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.StepRow
import ca.mslab.forge.data.db.WorkflowRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionsTest {
    private val steps = listOf(
        StepRow("backlog", "w", "flow", "Backlog", 0, "bg-neutral-400"),
        StepRow("doing", "w", "flow", "In Progress", 1, "bg-blue-500"),
        StepRow("done", "w", "flow", "Done", 3, "bg-green-500"),
    )
    private val workflows = listOf(WorkflowRow("flow", "w", "Kanban", 0))
    private fun task(id: String, step: String, state: String, at: String = "2026-10-30T00:00:00Z", parent: String? = null,
        pending: String? = null, repo: String? = null, error: Boolean = false) = TaskSummary(
        id = id, title = "Task $id", state = state, stepId = step, workflowId = "flow", parentId = parent, updatedAt = at,
        repositories = listOfNotNull(repo?.let { TaskRepository(it, "main") }),
        summary = TaskStatusSummary(lastActivityAt = at, pendingAction = pending, activeError = if (error) TaskError("boom") else null))

    @Test fun stepsFollowBoardOrderAndCollapseFinishedGroups() {
        val tasks = listOf(task("a", "doing", "IN_PROGRESS")) + (1..5).map { task("d$it", "done", "COMPLETED") }
        val sections = homeSections(tasks, steps, workflows, emptyMap(), byRepository = false, query = "")
        assertEquals(listOf("In Progress", "Done"), sections.map { it.title })
        assertEquals(false, sections[0].collapsedByDefault)
        assertEquals(true, sections[1].collapsedByDefault)
    }

    @Test fun requestsAndOpenErrorsArePinnedOnce() {
        val tasks = listOf(task("a", "doing", "IN_PROGRESS", pending = "permission"), task("b", "doing", "IN_PROGRESS", error = true),
            task("c", "done", "COMPLETED", error = true))
        val sections = homeSections(tasks, steps, workflows, emptyMap(), byRepository = false, query = "")
        assertEquals("Needs you", sections.first().title)
        assertEquals(setOf("a", "b"), sections.first().tasks.map { it.task.id }.toSet())
        assertTrue(sections.drop(1).none { section -> section.tasks.any { it.task.id in setOf("a", "b") } })
    }

    @Test fun subtasksNestUnderTheirParent() {
        val tasks = listOf(task("child", "doing", "IN_PROGRESS", at = "2026-10-30T03:00:00Z", parent = "parent"),
            task("parent", "doing", "IN_PROGRESS", at = "2026-10-30T01:00:00Z"), task("other", "doing", "IN_PROGRESS", at = "2026-10-30T02:00:00Z"))
        val placed = homeSections(tasks, steps, workflows, emptyMap(), byRepository = false, query = "").single().tasks
        assertEquals(listOf("other" to 0, "parent" to 0, "child" to 1), placed.map { it.task.id to it.depth })
    }

    @Test fun repositoryGroupingPutsOpenWorkFirst() {
        val repos = mapOf("r1" to Repository("r1", "forge"), "r2" to Repository("r2", "kandev"))
        val tasks = listOf(task("a", "done", "COMPLETED", repo = "r1"), task("b", "doing", "IN_PROGRESS", repo = "r2"))
        val sections = homeSections(tasks, steps, workflows, repos, byRepository = true, query = "")
        assertEquals(listOf("kandev", "forge"), sections.map { it.title })
        assertEquals(true, sections[1].collapsedByDefault)
    }

    @Test fun searchMatchesRepositoryNames() {
        val repos = mapOf("r1" to Repository("r1", "forge-mobile"))
        val tasks = listOf(task("a", "doing", "IN_PROGRESS", repo = "r1"), task("b", "doing", "IN_PROGRESS"))
        val sections = homeSections(tasks, steps, workflows, repos, byRepository = false, query = "mobile")
        assertEquals(listOf("a"), sections.flatMap { it.tasks }.map { it.task.id })
    }

    @Test fun statusReflectsTheAgentNotJustTheTaskState() {
        fun t(state: String, session: String? = null, pending: String? = null, running: Boolean = false, error: Boolean = false) =
            TaskSummary("x", "t", state, sessionState = session,
                summary = TaskStatusSummary(pendingAction = pending, running = running, activeError = if (error) TaskError("e") else null))
        assertEquals(AgentStatus.Approval, t("IN_PROGRESS", "WAITING_FOR_INPUT", pending = "permission").agentStatus())
        assertEquals(AgentStatus.Question, t("IN_PROGRESS", pending = "clarification").agentStatus())
        assertEquals(AgentStatus.Running, t("IN_PROGRESS", "RUNNING", running = true).agentStatus())
        assertEquals(AgentStatus.YourTurn, t("REVIEW", "WAITING_FOR_INPUT").agentStatus())
        assertEquals(AgentStatus.Completed, t("COMPLETED", "WAITING_FOR_INPUT").agentStatus())
        assertEquals(AgentStatus.Error, t("IN_PROGRESS", "WAITING_FOR_INPUT", error = true).agentStatus())
        assertEquals(AgentStatus.NotStarted, t("CREATED").agentStatus())
        // A completed task still sitting in Review was finished but never moved to Done.
        val inReview = t("COMPLETED", "WAITING_FOR_INPUT").copy(stepId = "review")
        assertEquals(AgentStatus.Finished, inReview.agentStatus(setOf("review")))
        assertEquals(AgentStatus.Completed, inReview.copy(stepId = "done").agentStatus(setOf("review")))
    }

    @Test fun openTaskStatusNeverCallsADoneTaskYourTurn() {
        val idle = ca.mslab.forge.data.AgentSession("s", state = "WAITING_FOR_INPUT")
        val open = openSteps(steps.map { if (it.id == "done") it.copy(completesTask = true) else it })
        assertEquals(AgentStatus.Completed, sessionStatus(task("a", "done", "COMPLETED"), idle, open, null))
        assertEquals(AgentStatus.YourTurn, sessionStatus(task("b", "doing", "IN_PROGRESS"), idle, open, null))
        assertEquals(AgentStatus.Running, sessionStatus(task("c", "done", "COMPLETED"), idle.copy(state = "RUNNING"), open, null))
        // A stale pending flag on the row doesn't override the live session.
        assertEquals(AgentStatus.YourTurn, sessionStatus(task("d", "doing", "IN_PROGRESS", pending = "permission"), idle, open, null))
    }

    @Test fun boardShowsEveryStepOfTheWorkflowWithUrgentFirst() {
        val tasks = listOf(task("a", "doing", "IN_PROGRESS", at = "2026-10-30T02:00:00Z"),
            task("b", "doing", "IN_PROGRESS", at = "2026-10-30T01:00:00Z", pending = "permission"), task("c", "done", "COMPLETED"))
        val columns = boardColumns(tasks, steps, "flow")
        assertEquals(listOf("Backlog", "In Progress", "Done"), columns.map { it.step.name })
        assertEquals(emptyList<String>(), columns[0].tasks.map { it.id })
        assertEquals(listOf("b", "a"), columns[1].tasks.map { it.id })
    }
}
