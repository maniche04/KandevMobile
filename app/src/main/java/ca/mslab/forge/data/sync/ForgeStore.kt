package ca.mslab.forge.data.sync

import ca.mslab.forge.data.*
import ca.mslab.forge.data.db.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal val storeJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

/** How long a state event waits for its matching status summary before the task is fetched instead. */
private const val SUMMARY_GRACE_MS = 2_000L

internal fun epochMillis(iso: String?): Long =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

private val RUNNING_SESSION = setOf("RUNNING", "STARTING")

internal fun TaskSummary.toRow(workspaceId: String) = TaskRow(
    id = id, workspaceId = workspaceId, workflowId = workflowId, stepId = stepId, parentId = parentId,
    state = state, sessionState = summary?.primarySession?.state?.takeIf { it.isNotBlank() } ?: sessionState,
    pendingAction = pendingAction?.takeIf { it.isNotBlank() },
    running = summary?.running == true || sessionState in RUNNING_SESSION,
    lastActivity = epochMillis(lastActivityAt), json = storeJson.encodeToString(TaskSummary.serializer(), this),
)

internal fun TaskRow.toTask(): TaskSummary? = runCatching { storeJson.decodeFromString(TaskSummary.serializer(), json) }.getOrNull()
internal fun SessionRow.toSession(): AgentSession? = runCatching { storeJson.decodeFromString(AgentSession.serializer(), json) }.getOrNull()
internal fun MessageRow.toMessage(): ChatMessage? = runCatching { storeJson.decodeFromString(ChatMessage.serializer(), json) }.getOrNull()
internal fun ChatMessage.toRow() = MessageRow(sessionId, id, epochMillis(createdAt), storeJson.encodeToString(ChatMessage.serializer(), this))

@kotlinx.serialization.Serializable
data class TodoItem(val description: String = "", val status: String = "", val priority: String = "")

/**
 * Keeps the database in step with the server: full revalidation on connect, targeted refetches for
 * events, and background prefetch of the conversations you are most likely to open next.
 * Reads never wait for the network; writes from the network never block the UI.
 */
internal class ForgeStore(
    private val scope: CoroutineScope,
    val db: ForgeDatabase,
    private val api: KandevApi,
) {
    private val dao = db.dao()
    private val refetch = ConcurrentHashMap<String, Job>()
    private val refreshLock = Mutex()
    private val prefetched = ConcurrentHashMap<String, Long>()

    // Reads -------------------------------------------------------------------------------------

    fun tasks(workspaceId: String): Flow<List<TaskSummary>> =
        dao.tasks(workspaceId).map { rows -> rows.mapNotNull { it.toTask() } }.flowOn(Dispatchers.Default)
    fun steps(workspaceId: String) = dao.steps(workspaceId)
    fun workflows(workspaceId: String) = dao.workflows(workspaceId)
    fun repositories(workspaceId: String): Flow<Map<String, Repository>> = dao.repositories(workspaceId).map { rows ->
        rows.mapNotNull { runCatching { storeJson.decodeFromString(Repository.serializer(), it.json) }.getOrNull() }.associateBy { it.id }
    }.flowOn(Dispatchers.Default)
    fun runtime(sessionId: String) = dao.runtime(sessionId)

    suspend fun cachedSessions(taskId: String): List<AgentSession> = dao.sessions(taskId).mapNotNull { it.toSession() }
    suspend fun cachedConversation(sessionId: String): CachedConversation? = withContext(Dispatchers.Default) {
        val meta = dao.conversation(sessionId) ?: return@withContext null
        CachedConversation(dao.messages(sessionId).mapNotNull { it.toMessage() }, meta.cursor, meta.hasOlder, meta.depth)
    }

    // Writes from the open screens ----------------------------------------------------------------

    suspend fun saveSessions(taskId: String, sessions: List<AgentSession>) = dao.replaceSessions(taskId,
        sessions.map { SessionRow(it.id, taskId, it.state, storeJson.encodeToString(AgentSession.serializer(), it)) })

    suspend fun saveConversation(sessionId: String, value: CachedConversation) = withContext(Dispatchers.Default) {
        dao.replaceConversation(ConversationRow(sessionId, value.cursor, value.hasOlder, value.depth, System.currentTimeMillis()),
            value.messages.map { it.toRow() })
    }

    // Revalidation --------------------------------------------------------------------------------

    /**
     * Tasks, workflow steps and repositories for one workspace, fetched in parallel. [structure] false
     * refreshes only the task list: workflows, steps and repositories rarely change during a brief drop.
     */
    suspend fun refreshWorkspace(workspaceId: String, structure: Boolean = true) = refreshLock.withLock {
        coroutineScope {
            val tasks = async { allTasks(workspaceId) }
            if (!structure) { dao.replaceTasks(workspaceId, tasks.await().map { it.toRow(workspaceId) }); return@coroutineScope }
            val workflows = async { runCatching { api.listWorkflows(workspaceId) }.getOrNull() }
            val steps = async { runCatching { api.listWorkflowSteps(workspaceId) }.getOrNull() }
            val repositories = async { runCatching { api.listRepositories(workspaceId) }.getOrNull() }
            val taskList = tasks.await()
            dao.replaceTasks(workspaceId, taskList.map { it.toRow(workspaceId) })
            val flows = workflows.await(); val stepList = steps.await()
            if (flows != null && stepList != null) dao.replaceStructure(workspaceId,
                flows.map { WorkflowRow(it.id, workspaceId, it.name, it.sortOrder) },
                stepList.map { StepRow(it.id, workspaceId, it.workflowId, it.name, it.position, it.color, it.completesTask) },
                repositories.await()?.map { RepositoryRow(it.id, workspaceId, it.name, storeJson.encodeToString(Repository.serializer(), it)) })
        }
    }

    private suspend fun allTasks(workspaceId: String): List<TaskSummary> {
        val first = api.listTasks(workspaceId)
        val rows = first.tasks.toMutableList()
        var page = 1
        while (rows.size < first.total && page < 20) {
            page++
            val next = api.listTasks(workspaceId, page)
            if (next.tasks.isEmpty()) break
            rows += next.tasks
        }
        return rows.distinctBy { it.id }
    }

    /** Archived tasks leave the board, as they do on the web. */
    private suspend fun keep(task: TaskSummary, workspaceId: String) =
        if (task.archivedAt.isNullOrBlank()) dao.upsertTasks(listOf(task.toRow(workspaceId))) else dao.deleteTask(task.id)

    /** Drops a task locally right after an explicit archive, before the server's event arrives. */
    suspend fun removeTask(taskId: String) = dao.deleteTask(taskId)

    /** Inserts a task the user just created so it shows before the event round trip. */
    suspend fun insertTask(task: TaskSummary, workspaceId: String) = dao.upsertTasks(listOf(task.toRow(workspaceId)))

    /**
     * Coalesces bursts of events for one task into a single authoritative fetch. A failed fetch is retried a
     * few times: the row is the only thing that clears a settled "needs approval", so one dropped request
     * must not leave it stale until the next full refresh.
     */
    fun refetchTask(taskId: String, delayMs: Long = 300) {
        refetch[taskId]?.cancel()
        refetch[taskId] = scope.launch {
            delay(delayMs)
            for (attempt in 0 until 3) {
                val done = runCatching {
                    val existing = dao.task(taskId)
                    val fresh = api.getTask(taskId)
                    // New tasks are inserted too; their workspace comes from the server row.
                    val workspace = existing?.workspaceId ?: fresh.workspaceId.takeIf { it.isNotBlank() } ?: return@runCatching
                    keep(fresh, workspace)
                }.onFailure { if (it is CancellationException) throw it }.isSuccess
                if (done) break
                delay(2_000L shl attempt)
            }
            refetch.remove(taskId)
        }
    }

    /** When each task's status summary last arrived, and when an event last implied a new one. */
    private val summaryAt = ConcurrentHashMap<String, Long>()
    private val summaryExpected = ConcurrentHashMap<String, Long>()
    private val summaryChecks = ConcurrentHashMap<String, Job>()

    /**
     * Kandev follows every session state and pending-action change with `task.status_summary.updated`, which
     * carries the running state and pending action. Fetch the task only if that summary doesn't arrive.
     */
    private fun expectSummary(taskId: String) {
        val now = System.nanoTime()
        // The summary may also arrive just before the event that announced the same change.
        summaryAt[taskId]?.let { if ((now - it) / 1_000_000 < SUMMARY_GRACE_MS) return }
        val first = summaryExpected.putIfAbsent(taskId, now) ?: now
        summaryChecks[taskId]?.cancel()
        // Wait for quiet, but never defer the check more than 10 s behind the first unanswered event.
        val wait = minOf(SUMMARY_GRACE_MS, maxOf(0L, 10_000L - (now - first) / 1_000_000))
        val check = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            val expected = summaryExpected[taskId] ?: return@launch
            // Conditional removals: a newer event may already have replaced this check.
            if (!summaryChecks.remove(taskId, coroutineContext.job)) return@launch
            summaryExpected.remove(taskId, expected)
            if ((summaryAt[taskId] ?: Long.MIN_VALUE) < expected) refetchTask(taskId, 0)
        }
        summaryChecks[taskId] = check
        check.start()
    }

    private fun summaryArrived(taskId: String) {
        summaryAt[taskId] = System.nanoTime()
        summaryChecks.remove(taskId)?.cancel()
        summaryExpected.remove(taskId)
    }

    /**
     * Warms sessions and the latest message page for tasks that are running, need you, or were
     * active recently, so opening them (or tapping a notification) is instant and works offline.
     */
    suspend fun prefetch(workspaceId: String, limit: Int = 8) {
        val rows = dao.tasksNow(workspaceId)
        val hot = rows.filter { it.running || it.pendingAction != null } +
            rows.sortedByDescending { it.lastActivity }.take(limit)
        for (row in hot.distinctBy { it.id }.take(limit + 4)) {
            val task = row.toTask() ?: continue
            val sessionId = task.primarySessionId ?: continue
            // Remembered across restarts through the saved conversation's sync time.
            val known = prefetched[sessionId] ?: dao.conversation(sessionId)?.syncedAt
            if (known != null && known >= row.lastActivity) continue
            runCatching {
                coroutineScope {
                    val sessions = async { api.listSessions(task.id) }
                    val page = async { api.listMessages(sessionId) }
                    saveSessions(task.id, sessions.await())
                    val messages = page.await()
                    val cached = dao.conversation(sessionId)
                    // Never shrink a deeper conversation the user already paged into.
                    if (cached == null || cached.depth <= 1) saveConversation(sessionId,
                        CachedConversation(orderedMessages(messages.messages), messages.cursor.takeIf { it.isNotBlank() }, messages.hasMore, 1))
                }
                prefetched[sessionId] = row.lastActivity
            }
        }
    }

    // Live events ---------------------------------------------------------------------------------

    suspend fun apply(event: WireMessage) {
        val payload = event.payload
        val taskId = payload["task_id"]?.jsonPrimitive?.contentOrNull
        val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull
        when (event.action) {
            "task.status_summary.updated" -> {
                val id = taskId ?: return
                summaryArrived(id)
                val row = dao.task(id) ?: return refetchTask(id, 0)
                val task = row.toTask() ?: return
                val summary = payload["status_summary"]?.let {
                    runCatching { storeJson.decodeFromJsonElement(TaskStatusSummary.serializer(), it) }.getOrNull()
                } ?: return
                dao.upsertTasks(listOf(task.copy(summary = summary, sessionState = summary.primarySession?.state ?: task.sessionState,
                    activity = summary.activity).toRow(row.workspaceId)))
            }
            "task.created", "task.updated" -> {
                val id = taskId ?: payload["id"]?.jsonPrimitive?.contentOrNull ?: return
                val workspace = payload["workspace_id"]?.jsonPrimitive?.contentOrNull ?: dao.task(id)?.workspaceId
                val parsed = runCatching { storeJson.decodeFromJsonElement(TaskSummary.serializer(), payload) }.getOrNull()
                if (parsed != null && workspace != null && parsed.title.isNotBlank()) {
                    val previous = dao.task(id)?.toTask()
                    // Event payloads may omit the status summary; keep the richer cached one.
                    keep(parsed.copy(summary = parsed.summary ?: previous?.summary), workspace)
                }
                refetchTask(id)
            }
            "task.deleted" -> (taskId ?: payload["id"]?.jsonPrimitive?.contentOrNull)?.let { dao.deleteTask(it) }
            "task.state_changed", "task.reordered" -> taskId?.let { refetchTask(it) }
            "session.pending_action_changed" -> {
                val id = taskId ?: return
                val row = dao.task(id) ?: return refetchTask(id)
                val pending = payload["pending_action"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                if (row.pendingAction != pending) expectSummary(id)
            }
            "session.state_changed" -> {
                val id = sessionId ?: return
                val state = payload["new_state"]?.jsonPrimitive?.contentOrNull
                // Most of these events only carry metadata (context window); only a real state change of the
                // task's primary session can change its row, and the status summary that follows carries it.
                if (state != null && taskId != null) {
                    val row = dao.task(taskId)
                    val primary = row?.toTask()?.let { it.summary?.primarySession?.id?.ifBlank { null } ?: it.primarySessionId }
                    if (row == null) refetchTask(taskId)
                    else if ((primary == null || primary == id) && row.sessionState != state) expectSummary(taskId)
                }
                if (state != null) trackTurn(id, taskId, state)
                dao.session(id)?.let { row ->
                    val session = row.toSession()
                    if (session != null && state != null) dao.upsertSessions(listOf(row.copy(state = state,
                        json = storeJson.encodeToString(AgentSession.serializer(), session.copy(state = state)))))
                }
                val window = (payload["metadata"] ?: payload["session_metadata"])?.jsonObject?.get("context_window")?.jsonObject
                if (window != null) updateRuntime(id, taskId) {
                    it.copy(contextUsed = window["used"]?.jsonPrimitive?.longOrNull ?: it.contextUsed,
                        contextSize = window["size"]?.jsonPrimitive?.longOrNull ?: it.contextSize)
                }
            }
            "session.todos_updated" -> {
                val id = sessionId ?: return
                val todos = payload["entries"]?.let {
                    runCatching { storeJson.decodeFromJsonElement(ListSerializer(TodoItem.serializer()), it) }.getOrNull()
                } ?: return
                updateRuntime(id, taskId) { it.copy(todosJson = storeJson.encodeToString(ListSerializer(TodoItem.serializer()), todos)) }
            }
            "session.message.added", "session.message.updated" -> {
                val id = sessionId ?: return
                // Live events carry `message_id`; the REST shape uses `id`.
                val normalized = if (payload.containsKey("id")) payload else payload["message_id"]?.let { JsonObject(payload + ("id" to it)) } ?: return
                val message = runCatching { storeJson.decodeFromJsonElement(ChatMessage.serializer(), normalized) }.getOrNull() ?: return
                // Settled (e.g. auto-approved) permission requests don't change what the agent is doing.
                val line = if (message.author == "agent") activityLine(message) else ""
                if (line.isNotBlank()) updateRuntime(id, taskId) { it.copy(activity = line) }
                // Keep warm conversations current so opening them shows the latest rows instantly.
                if (dao.conversation(id) != null) dao.upsertMessages(listOf(message.toRow()))
            }
        }
    }

    /** A turn ended (RUNNING → waiting/completed/failed). Emitted from session events, so even a two-second turn is seen. */
    data class TurnEnded(val taskId: String, val sessionId: String, val state: String)
    private val _turns = kotlinx.coroutines.flow.MutableSharedFlow<TurnEnded>(extraBufferCapacity = 64)
    val turns: kotlinx.coroutines.flow.SharedFlow<TurnEnded> = _turns
    private val sessionStates = ConcurrentHashMap<String, String>()

    private suspend fun trackTurn(sessionId: String, taskId: String?, state: String) {
        val before = sessionStates.put(sessionId, state)
        val task = taskId ?: dao.session(sessionId)?.taskId ?: return
        // A turn that began before this connection (app opened mid-turn, process restart) still counts:
        // fall back to the cached row's running flag when this session's start wasn't seen.
        val wasRunning = before in RUNNING_SESSION || (before == null && dao.task(task)?.running == true)
        if (state in RUNNING_SESSION) {
            // Show "running" immediately instead of waiting for the task refetch.
            dao.task(task)?.let { row -> if (!row.running) dao.upsertTasks(listOf(row.copy(running = true, sessionState = state))) }
        } else if (wasRunning && state in setOf("WAITING_FOR_INPUT", "COMPLETED", "FAILED", "CANCELLED")) {
            _turns.tryEmit(TurnEnded(task, sessionId, state))
        }
    }

    /** Latest agent reply for a session: cached rows first, else one small fetch. */
    suspend fun lastAgentReply(sessionId: String): String? {
        val cached = dao.messages(sessionId).takeLast(20).mapNotNull { it.toMessage() }
            .lastOrNull { it.author == "agent" && it.type in setOf("message", "content") }?.content
        return cached ?: runCatching {
            api.listMessages(sessionId).messages.firstOrNull { it.author == "agent" && it.type in setOf("message", "content") }?.content
        }.getOrNull()
    }

    /** Writes only real changes: every write wakes the task list and the live notifications. */
    private suspend fun updateRuntime(sessionId: String, taskId: String?, change: (RuntimeRow) -> RuntimeRow) {
        val existing = dao.runtimeNow(sessionId)
        val current = existing ?: RuntimeRow(sessionId, taskId.orEmpty(), null, 0, 0, null, 0)
        val next = change(current).copy(taskId = taskId ?: current.taskId)
        if (existing != null && next == current) return
        dao.upsertRuntime(next.copy(updatedAt = System.currentTimeMillis()))
    }


    /** Evicts conversations not touched for two weeks. Tasks and structure are small and kept. */
    suspend fun trim() = dao.run {
        val before = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
        evictMessages(before); evictConversations(before)
    }
}

/** One short line describing what an agent is doing, for lists and notifications. */
internal fun activityLine(message: ChatMessage): String {
    val title = message.metadata?.title?.takeIf { it.isNotBlank() }
        ?: message.metadata?.normalized?.shell?.command?.takeIf { it.isNotBlank() }
    val text = when (message.type) {
        "tool_execute", "script_execution" -> "Running ${title ?: message.content.lineSequence().firstOrNull().orEmpty()}"
        "tool_read" -> "Reading ${title ?: ""}"
        "tool_edit", "tool_write" -> "Editing ${title ?: ""}"
        "tool_search", "tool_grep", "tool_glob" -> "Searching ${title ?: ""}"
        "thinking" -> "Thinking…"
        "message", "content" -> message.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // Once settled (e.g. auto-approved), say what the approved action is doing instead of "waiting".
        "permission_request" -> if (message.isOpenPermission()) "Waiting for approval"
            else if (message.isDeniedPermission()) "" else "Running ${message.metadata?.actionDetails?.command ?: message.content.lineSequence().firstOrNull().orEmpty()}"
        "clarification_request" -> "Asking a question"
        else -> title ?: message.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
    return text.trim().take(140)
}
