package ca.mslab.forge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class TaskSummary(
    val id: String, val title: String, val state: String,
    @SerialName("primary_session_id") val primarySessionId: String? = null,
    val description: String = "",
    val priority: String = "",
    val repositories: List<TaskRepository> = emptyList(),
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("primary_agent_name") val agentName: String? = null,
    @SerialName("primary_agent_profile_id") val agentProfileId: String? = null,
    @SerialName("primary_executor_name") val executorName: String? = null,
    @SerialName("primary_executor_type") val executorType: String? = null,
    @SerialName("primary_session_state") val sessionState: String? = null,
    @SerialName("foreground_activity") val activity: String? = null,
    @SerialName("active_subagent_count") val subagents: Int = 0,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val autopilot: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("status_summary") val summary: TaskStatusSummary? = null,
    @SerialName("workflow_id") val workflowId: String? = null,
    @SerialName("workflow_step_id") val stepId: String? = null,
    @SerialName("primary_session_pending_action") val sessionPendingAction: String? = null,
    @SerialName("task_pending_action") val taskPendingAction: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    val position: Double = 0.0,
    @SerialName("workspace_id") val workspaceId: String = "",
) {
    /**
     * A pending approval or question; idle WAITING_FOR_INPUT alone is not a request. The status summary is a
     * complete replacement value: once present, its omitted `pending_action` means "nothing pending", so the
     * older task fields must not resurrect a request that was answered or auto-approved (same rule as the web).
     */
    val pendingAction: String? get() = if (summary != null) summary.pendingAction?.ifBlank { null }
        else taskPendingAction ?: sessionPendingAction?.takeIf { sessionState == "WAITING_FOR_INPUT" }
    val lastActivityAt: String get() = summary?.lastActivityAt ?: updatedAt
}
@Serializable
data class TaskRepository(@SerialName("repository_id") val repositoryId: String = "",
    @SerialName("base_branch") val baseBranch: String = "")
@Serializable
data class TaskStatusSummary(
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
    @SerialName("has_running_session") val running: Boolean = false,
    @SerialName("active_error") val activeError: TaskError? = null,
    val git: TaskGitSummary? = null,
    @SerialName("pending_action") val pendingAction: String? = null,
    @SerialName("foreground_activity") val activity: String? = null,
    @SerialName("primary_session") val primarySession: SessionRef? = null,
    @SerialName("queued_prompt_count") val queuedPrompts: Int = 0,
)
@Serializable
data class SessionRef(val id: String = "", val state: String = "")
@Serializable
data class WorkflowStep(val id: String, @SerialName("workflow_id") val workflowId: String = "", val name: String = "",
    val position: Int = 0, val color: String = "",
    @SerialName("complete_task_on_enter") val completesTask: Boolean = false)
/** Server answer to "what happens if this task moves there?" (read-only). */
@Serializable
data class MovePreview(val outcome: String = "", val dispatch: String = "", val recipient: MoveRecipient? = null,
    @SerialName("context_reset") val contextReset: Boolean = false)
@Serializable
data class MoveRecipient(@SerialName("profile_name") val profileName: String? = null)
@Serializable
internal data class WorkflowStepList(val steps: List<WorkflowStep> = emptyList())
@Serializable
data class AgentProfile(val id: String, val name: String = "", val model: String = "")
@Serializable
data class AgentInfo(val id: String = "", val name: String = "", val profiles: List<AgentProfile>? = null)
@Serializable
internal data class AgentList(val agents: List<AgentInfo> = emptyList())
@Serializable
data class ExecutorProfile(val id: String, val name: String = "")
@Serializable
data class Executor(val id: String, val name: String = "", val type: String = "", val status: String = "",
    val profiles: List<ExecutorProfile>? = null)
@Serializable
internal data class ExecutorList(val executors: List<Executor> = emptyList())
/** What a new task needs to start: agent profiles and executors, cached for offline display. */
@Serializable
data class LaunchOptions(val agents: List<AgentInfo> = emptyList(), val executors: List<Executor> = emptyList())
@Serializable
data class Workflow(val id: String, val name: String = "", @SerialName("sort_order") val sortOrder: Int = 0)
@Serializable
internal data class WorkflowList(val workflows: List<Workflow> = emptyList())
@Serializable
data class TaskError(val preview: String = "", val category: String = "")
@Serializable
data class TaskGitSummary(val additions: Int = 0, val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0, val ahead: Int = 0)
@Serializable
data class TaskPage(val tasks: List<TaskSummary>, val total: Int)
@Serializable
data class Repository(val id: String, val name: String,
    @SerialName("default_branch") val defaultBranch: String = "",
    @SerialName("source_type") val sourceType: String = "")
@Serializable
internal data class RepositoryList(val repositories: List<Repository> = emptyList())
@Serializable
data class AgentSession(val id: String, val name: String = "", val state: String,
    @SerialName("task_environment_id") val environmentId: String? = null,
    @SerialName("cancellation_pending") val cancellationPending: Boolean = false,
    @SerialName("base_branch") val baseBranch: String = "",
    @SerialName("executor_id") val executorId: String = "",
    @SerialName("is_primary") val primary: Boolean = false,
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("foreground_activity") val activity: String? = null,
    @SerialName("agent_profile_id") val agentProfileId: String = "",
    @SerialName("queue_incarnation_id") val queueIncarnationId: String = "",
    @SerialName("pending_action") val pendingAction: String? = null,
    @SerialName("workspace_path") val workspacePath: String = "",
    /** A send right now would be delivered into the running turn (mid-turn steering) instead of queued. */
    @SerialName("supports_steering") val supportsSteering: Boolean = false,
    @SerialName("error_message") val errorMessage: String = "")
@Serializable
internal data class SessionList(val sessions: List<AgentSession>, val total: Int)
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("author_type") val author: String,
    val content: String,
    val type: String = "message",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("requests_input") val requestsInput: Boolean = false,
    @SerialName("turn_id") val turnId: String? = null,
    val metadata: MessageMetadata? = null,
)
@Serializable
data class MessagePage(
    val messages: List<ChatMessage>,
    @SerialName("has_more") val hasMore: Boolean,
    val cursor: String,
)
@Serializable
internal data class ConversationOperation(
    val kind: String, val entity: String, val id: String, val message: JsonObject? = null, val turn: SessionTurn? = null,
)
@Serializable
internal data class ConversationChange(
    @SerialName("protocol_version") val protocol: Int,
    @SerialName("scope_id") val scopeId: String,
    @SerialName("session_id") val sessionId: String,
    val epoch: String,
    @SerialName("base_revision") val baseRevision: String,
    val revision: String,
    val reset: Boolean = false,
    val check: Boolean = false,
    val operations: List<ConversationOperation>,
)

/** One v2 subscription. A gap always causes re-subscription and a fresh HTTP snapshot. */
internal class ConversationProjection(
    private val sessionId: String, private val scopeId: String,
    private val epoch: String, initialRevision: String,
) {
    private var revision = decimal(initialRevision)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun apply(change: ConversationChange, current: List<ChatMessage>): List<ChatMessage> {
        require(change.protocol == 2 && change.sessionId == sessionId && change.scopeId == scopeId)
        require(!change.reset && change.epoch == epoch)
        val next = decimal(change.revision)
        if (change.check) { require(next == revision); return current }
        val base = decimal(change.baseRevision)
        require(next > base && change.operations.size <= 256)
        if (next <= revision) return current
        require(base == revision) { "Conversation revision gap" }
        val rows = current.associateBy { it.id }.toMutableMap()
        for (operation in change.operations) {
            require(operation.kind in setOf("upsert", "remove") && operation.entity in setOf("message", "turn"))
            if (operation.entity != "message") continue
            if (operation.kind == "remove") {
                rows.remove(operation.id)
            } else {
                val message = json.decodeFromJsonElement(ChatMessage.serializer(), requireNotNull(operation.message))
                require(message.id == operation.id && message.sessionId == sessionId)
                val previous = rows[message.id]
                // HTTP hydration can already contain a newer version than a buffered notification.
                if (previous == null || message.changedAt >= previous.changedAt) rows[message.id] = message
            }
        }
        revision = next
        return orderedMessages(rows.values)
    }

    private fun decimal(value: String): java.math.BigInteger {
        require(value.matches(Regex("0|[1-9][0-9]*")))
        return value.toBigInteger()
    }
}

private val ChatMessage.changedAt get() = java.time.Instant.parse(updatedAt ?: createdAt)
internal fun orderedMessages(messages: Collection<ChatMessage>): List<ChatMessage> =
    messages.distinctBy { it.id }.sortedWith(compareBy<ChatMessage> { java.time.Instant.parse(it.createdAt) }.thenBy { it.id })

@Serializable
data class MessageMetadata(val title: String? = null, val status: String? = null, val normalized: NormalizedTool? = null,
    @SerialName("pending_id") val pendingId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val options: List<PermissionOption> = emptyList(),
    @SerialName("action_type") val actionType: String? = null,
    @SerialName("action_details") val actionDetails: PermissionDetails? = null,
    val question: InputQuestion? = null,
    @SerialName("question_index") val questionIndex: Int? = null,
    @SerialName("question_total") val questionTotal: Int? = null,
    @SerialName("agent_disconnected") val agentDisconnected: Boolean = false,
    val context: String? = null, val response: QuestionAnswer? = null,
    val attachments: List<MessageAttachment> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("permission_resolution") val resolution: PermissionResolution? = null)
/** How a permission request was settled; `source = auto_approve` means a provider policy, not a person. */
@Serializable
data class PermissionResolution(val source: String? = null, @SerialName("actor_kind") val actorKind: String? = null,
    val result: String? = null, @SerialName("option_kind") val optionKind: String? = null)
@Serializable
data class NormalizedTool(@SerialName("shell_exec") val shell: ShellExecution? = null,
    @SerialName("read_file") val readFile: FileRef? = null,
    @SerialName("modify_file") val modifyFile: FileRef? = null)
@Serializable
data class FileRef(@SerialName("file_path") val path: String = "")

/** The file a read/edit tool call touched, if any. */
internal val ChatMessage.toolFilePath: String?
    get() = (metadata?.normalized?.readFile?.path ?: metadata?.normalized?.modifyFile?.path)?.takeIf { it.isNotBlank() }
@Serializable
data class ShellExecution(val command: String = "", val output: ShellOutputSummary? = null)
@Serializable
data class ShellOutputSummary(@SerialName("has_output") val hasOutput: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null)
@Serializable
data class ShellOutput(val stdout: String = "", val stderr: String = "", @SerialName("exit_code") val exitCode: Int? = null)
@Serializable
data class ShellOutputSnapshot(val output: ShellOutput)

internal data class ConversationGroup(val messages: List<ChatMessage>) { val id get() = messages.first().id }
internal fun isToolMessage(message: ChatMessage) = message.type.startsWith("tool_") || message.type in setOf("log", "script_execution")

/** The agent looked at an image; these stay out of folded tool groups so their preview is always visible. */
internal val ChatMessage.imageReadPath: String?
    get() = metadata?.normalized?.readFile?.path?.takeIf { it.isNotBlank() && isImagePath(it) }

/**
 * Consecutive tool calls of one turn fold into a group. Image reads stand alone, and no group spans one of
 * the [breaks] (server times your unconfirmed prompts were sent after), so their bubbles can sit in between.
 */
internal fun conversationGroups(messages: List<ChatMessage>, breaks: Collection<String> = emptyList()): List<ConversationGroup> {
    val cuts = breaks.map(java.time.Instant::parse)
    val groups = mutableListOf<MutableList<ChatMessage>>()
    for (message in messages) {
        if (message.type == "thinking" && message.content.isBlank()) continue
        val previous = groups.lastOrNull()?.lastOrNull()
        val cut = previous != null && cuts.any { java.time.Instant.parse(previous.createdAt) <= it && it < java.time.Instant.parse(message.createdAt) }
        if (isToolMessage(message) && previous != null && isToolMessage(previous) && previous.turnId == message.turnId && !message.requestsInput &&
            message.imageReadPath == null && previous.imageReadPath == null && !cut)
            groups.last().add(message)
        else groups.add(mutableListOf(message))
    }
    return groups.map { ConversationGroup(it) }
}

/**
 * Where a prompt you sent belongs among [groups]: after the last group that began no later than [after],
 * the server time of the newest message on screen when you sent it (so device clock skew can't misplace it).
 * -1 means before every group.
 */
internal fun outgoingAnchor(groups: List<ConversationGroup>, after: String?): Int {
    val at = after?.let(java.time.Instant::parse) ?: return -1
    return groups.indexOfLast { java.time.Instant.parse(it.messages.first().createdAt) <= at }
}

private val OPEN_REQUEST_STATES = setOf("pending", "requested", "waiting", "")

/** A permission request that still needs someone; everything else is history, not a request. */
internal fun ChatMessage.isOpenPermission() = type == "permission_request" &&
    (requestsInput || (metadata?.status ?: "pending") in OPEN_REQUEST_STATES)

internal fun ChatMessage.isAutoApproved() = metadata?.resolution?.let { it.source == "auto_approve" || it.actorKind == "automation" } == true

internal fun ChatMessage.isDeniedPermission() = type == "permission_request" &&
    (metadata?.status in setOf("rejected", "denied", "cancelled") || metadata?.resolution?.result in setOf("rejected", "denied"))

/**
 * Chat rows to render: settled approvals are folded into the tool call they guarded (shown as a
 * badge), so an auto-approved command doesn't appear twice. Open and denied requests stay visible.
 */
internal fun visibleMessages(messages: List<ChatMessage>): List<ChatMessage> = messages.filter { message ->
    message.type != "permission_request" || message.isOpenPermission() || message.isDeniedPermission() ||
        message.metadata?.toolCallId == null
}

/** Settled approvals keyed by the tool call they guarded. */
internal fun approvalsByToolCall(messages: List<ChatMessage>): Map<String, ChatMessage> = messages
    .filter { it.type == "permission_request" && !it.isOpenPermission() && it.metadata?.toolCallId != null }
    .associateBy { it.metadata!!.toolCallId!! }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
internal fun isImagePath(path: String) = path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** Something that looks like a file worth opening: has a name with an extension, not a URL or directory. */
internal fun isOpenableFilePath(path: String): Boolean {
    val trimmed = path.trim().trim('`', '"', '\'', '(', ')', ',', ';', ':')
    if (trimmed.isEmpty() || trimmed.endsWith("/") || trimmed.contains("://") || trimmed.contains(' ')) return false
    val name = trimmed.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "")
    // Extensions contain a letter, so versions like v0.98.0 aren't mistaken for files.
    return name.contains('.') && extension.length in 1..8 && extension.all { it.isLetterOrDigit() } && extension.any { it.isLetter() } &&
        (trimmed.contains('/') || name.length > extension.length + 1)
}

/** Absolute paths inside the session workspace become workspace-relative; others are left as they are. */
internal fun workspaceRelative(path: String, workspacePath: String): String {
    val clean = path.trim()
    val root = workspacePath.trimEnd('/')
    return when {
        root.isNotEmpty() && clean == root -> ""
        root.isNotEmpty() && clean.startsWith("$root/") -> clean.removePrefix("$root/")
        else -> clean.removePrefix("./")
    }
}

/** Markdown destinations without a URI scheme refer to workspace files or folders. */
internal fun workspaceLinkPath(destination: String): String? {
    if (destination.isBlank() || destination.startsWith('#') || destination.startsWith("//")) return null
    val withoutLine = destination.replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(withoutLine)) return null
    val decoded = runCatching {
        java.net.URLDecoder.decode(withoutLine.replace("+", "%2B"), "UTF-8")
    }.getOrNull() ?: return null
    return decoded.substringBefore('#').replace(Regex(":\\d+(?::\\d+)?$"), "").takeIf { it.isNotBlank() }
}
