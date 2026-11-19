package ca.mslab.forge.ui

import ca.mslab.forge.data.Repository
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.StepRow
import ca.mslab.forge.data.db.WorkflowRow
import ca.mslab.forge.data.sync.epochMillis

internal val DONE_STATES = setOf("COMPLETED", "CANCELLED", "ARCHIVED", "DONE")

/** A task placed in a section; depth 1 means it is nested under its parent. */
internal data class PlacedTask(val task: TaskSummary, val depth: Int)

internal data class HomeSection(
    val key: String, val title: String, val subtitle: String?, val color: String?,
    val tasks: List<PlacedTask>, val collapsedByDefault: Boolean, val attention: Boolean = false,
)

internal fun TaskSummary.isDone() = state in DONE_STATES

/** Needs a decision from you: an open approval/question, or an error on a task that is still open. */
internal fun TaskSummary.needsYou() = pendingAction != null || (!isDone() && summary?.activeError != null)

/**
 * Builds the home list: a pinned "Needs you" section, then either workflow steps (board order) or
 * repositories. Finished groups start collapsed; subtasks nest under a parent in the same group.
 */
internal fun homeSections(
    tasks: List<TaskSummary>, steps: List<StepRow>, workflows: List<WorkflowRow>,
    repositories: Map<String, Repository>, byRepository: Boolean, query: String,
): List<HomeSection> {
    val visible = filterTasks(tasks, repositories, query)
    val sections = mutableListOf<HomeSection>()
    val pinned = visible.filter { it.needsYou() }
    if (pinned.isNotEmpty()) sections += HomeSection("needs-you", "Needs you", null, null, nest(pinned), false, attention = true)
    val rest = visible - pinned.toSet()

    if (byRepository) {
        rest.groupBy { it.repositories.firstOrNull()?.repositoryId }.map { (id, group) ->
            val name = id?.let { repositories[it]?.name } ?: if (id == null) "No repository" else "Repository"
            val open = group.count { !it.isDone() }
            val ordered = group.sortedWith(compareBy<TaskSummary> { it.isDone() }.thenByDescending { epochMillis(it.lastActivityAt) })
            HomeSection("repo:$id", name, if (open > 0) "$open open" else null, null, nest(ordered), open == 0)
        }.sortedWith(compareBy<HomeSection> { it.collapsedByDefault }.thenByDescending { s ->
            s.tasks.maxOfOrNull { epochMillis(it.task.lastActivityAt) } ?: 0L
        }).let(sections::addAll)
    } else {
        val workflowOrder = workflows.associate { it.id to it.sortOrder }
        val workflowNames = workflows.associate { it.id to it.name }
        val byStep = rest.groupBy { it.stepId }
        // Name the workflow only when open work spans more than one.
        val usedWorkflows = rest.filter { !it.isDone() }.mapNotNull { it.workflowId }.toSet()
        val orderedSteps = steps.sortedWith(compareBy<StepRow> { workflowOrder[it.workflowId] ?: Int.MAX_VALUE }.thenBy { it.position })
        for (step in orderedSteps) {
            val group = byStep[step.id].orEmpty()
            if (group.isEmpty()) continue
            val done = group.all { it.isDone() }
            val ambiguous = orderedSteps.count { it.name.equals(step.name, true) && byStep[it.id].orEmpty().isNotEmpty() } > 1
            val subtitle = workflowNames[step.workflowId]?.takeIf { usedWorkflows.size > 1 || ambiguous }
            sections += HomeSection("step:${step.id}", step.name, subtitle, step.color,
                nest(group.sortedByDescending { epochMillis(it.lastActivityAt) }), done && group.size > 3)
        }
        val known = steps.map { it.id }.toSet()
        val other = rest.filter { it.stepId == null || it.stepId !in known }
        if (other.isNotEmpty()) sections += HomeSection("step:other", "Other", null, null,
            nest(other.sortedByDescending { epochMillis(it.lastActivityAt) }), other.all { it.isDone() })
    }
    return sections
}

/** Tasks matching a search over title, ID, agent and repository. */
internal fun filterTasks(tasks: List<TaskSummary>, repositories: Map<String, Repository>, query: String): List<TaskSummary> {
    val needle = query.trim().lowercase()
    return if (needle.isEmpty()) tasks else tasks.filter { task ->
        task.title.lowercase().contains(needle) || task.externalId.orEmpty().lowercase().contains(needle) ||
            task.agentName.orEmpty().lowercase().contains(needle) ||
            task.repositories.any { repositories[it.repositoryId]?.name?.lowercase()?.contains(needle) == true }
    }
}

/** One board column: a workflow step and its tasks, most recent first. */
internal data class BoardColumn(val step: StepRow, val tasks: List<TaskSummary>)

/** The Kanban board for one workflow: every step in board order, including empty ones. */
internal fun boardColumns(tasks: List<TaskSummary>, steps: List<StepRow>, workflowId: String?): List<BoardColumn> {
    val byStep = tasks.groupBy { it.stepId }
    return steps.filter { it.workflowId == workflowId }.sortedBy { it.position }.map { step ->
        BoardColumn(step, byStep[step.id].orEmpty().sortedWith(compareByDescending<TaskSummary> { it.needsYou() }
            .thenByDescending { epochMillis(it.lastActivityAt) }))
    }
}

/** Keeps input order for roots and places each child right after its parent. */
internal fun nest(ordered: List<TaskSummary>): List<PlacedTask> {
    val ids = ordered.map { it.id }.toSet()
    val children = ordered.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId }
    val result = mutableListOf<PlacedTask>()
    for (task in ordered) {
        if (task.parentId != null && task.parentId in ids) continue
        result += PlacedTask(task, 0)
        children[task.id]?.forEach { result += PlacedTask(it, 1) }
    }
    return result
}

/** What the agent behind a task is doing, from the developer's point of view. */
internal enum class AgentStatus(val label: String) {
    Approval("Needs approval"), Question("Question for you"), Running("Running"),
    /** Kandev's `foreground_activity: background`: the turn is held open only by background work (a subagent, a monitor). */
    Background("Background work"), Failed("Failed"),
    Error("Error"), YourTurn("Your turn"), NotStarted("Not started"), Completed("Completed"), Cancelled("Cancelled"),
    /** The agent finished, but the card still sits in a non-final step such as Review. */
    Finished("Finished · not moved"),
}

internal val AgentStatus.working get() = this == AgentStatus.Running || this == AgentStatus.Background

internal fun TaskSummary.isRunning() = summary?.running == true || sessionState in setOf("RUNNING", "STARTING")

/**
 * Task state alone is misleading (a COMPLETED task can sit in Review with an idle agent), so the
 * status combines the open request, the session and the task state, most urgent first.
 */
internal fun TaskSummary.agentStatus(openSteps: Set<String> = emptySet()): AgentStatus = when {
    pendingAction == "permission" -> AgentStatus.Approval
    pendingAction != null -> AgentStatus.Question
    isRunning() && (activity ?: summary?.activity) == "background" -> AgentStatus.Background
    isRunning() -> AgentStatus.Running
    state == "FAILED" || sessionState == "FAILED" -> AgentStatus.Failed
    !isDone() && summary?.activeError != null -> AgentStatus.Error
    state == "CANCELLED" -> AgentStatus.Cancelled
    isDone() && stepId != null && stepId in openSteps -> AgentStatus.Finished
    isDone() -> AgentStatus.Completed
    sessionState == "WAITING_FOR_INPUT" || sessionState == "COMPLETED" || state in setOf("REVIEW", "IN_PROGRESS") -> AgentStatus.YourTurn
    else -> AgentStatus.NotStarted
}

/**
 * The open task's status: the selected session (live over the socket) overrides the task row, but a
 * task that is done stays done — an idle agent on a finished task is not "your turn".
 */
internal fun sessionStatus(task: TaskSummary?, session: ca.mslab.forge.data.AgentSession?, openSteps: Set<String>,
    pending: ca.mslab.forge.data.PendingInput?): AgentStatus {
    val state = session?.state
    return when {
        pending is ca.mslab.forge.data.PendingInput.Permission -> AgentStatus.Approval
        pending != null -> AgentStatus.Question
        state in setOf("RUNNING", "STARTING") -> if (session?.activity == "background") AgentStatus.Background else AgentStatus.Running
        state == "FAILED" -> AgentStatus.Failed
        task == null -> if (state == "CREATED") AgentStatus.NotStarted else AgentStatus.YourTurn
        // The session is not running, so the row's "running" flag is stale; drop it and let the task decide.
        else -> task.copy(sessionState = state ?: task.sessionState, summary = task.summary?.copy(running = false, pendingAction = null),
            sessionPendingAction = null, taskPendingAction = null).agentStatus(openSteps).let {
            if (it == AgentStatus.NotStarted && state == "WAITING_FOR_INPUT") AgentStatus.YourTurn else it
        }
    }
}

/** Steps that don't complete a task on entry (Backlog, In Progress, Review…); a done task there wasn't moved. */
internal fun openSteps(steps: List<StepRow>): Set<String> = steps.filterNot { it.completesTask }.map { it.id }.toSet()
