package ca.mslab.forge.data

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * What you can do to a session's agent. Interrupt only stops the current turn (`agent.cancel`) and keeps the
 * agent alive, as the web's composer button does; End stops the agent process (`session.stop`). An ended
 * session comes back through `session.recover`: Resume keeps the conversation, Fresh start reuses the session
 * without the old context, New branch resumes after the original branch was lost.
 */
internal enum class SessionAction(val label: String) {
    Start("Start agent"), Interrupt("Stop"), End("End agent session"),
    Resume("Resume"), FreshStart("Start fresh"), NewBranch("Continue on a new branch"),
}

internal val ENDED_STATES = setOf("COMPLETED", "FAILED", "CANCELLED")

/** The actions a session offers in its current state, most likely first. */
internal fun sessionActions(session: AgentSession?): List<SessionAction> = when {
    session == null || session.cancellationPending -> emptyList()
    session.state in setOf("RUNNING", "STARTING") -> listOf(SessionAction.Interrupt, SessionAction.End)
    session.state == "WAITING_FOR_INPUT" -> listOf(SessionAction.End)
    session.state == "CREATED" -> listOf(SessionAction.Start)
    session.state == "COMPLETED" -> listOf(SessionAction.Resume)
    session.state in ENDED_STATES -> listOf(SessionAction.Resume, SessionAction.FreshStart)
    else -> emptyList()
}

internal data class SessionActionContext(val taskId: String, val session: AgentSession?, val live: Boolean)

/** Lifecycle actions are never scheduled by navigation, restoration or reconnection. */
internal class SessionActions(
    private val scope: CoroutineScope,
    private val context: () -> SessionActionContext,
    private val refresh: suspend () -> AgentSession,
    private val request: suspend (String, JsonObject) -> JsonObject,
    private val updated: (AgentSession) -> Unit,
) {
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    /** Resume failed because the session's branch is gone; offer to continue on a new one. */
    var branchLost by mutableStateOf(false); private set
    private var blocked by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var generation = 0
    private fun key(session: AgentSession?) = "${session?.id}:${session?.state}:${session?.cancellationPending}"
    val available get() = sessionActions(context().session).let { if (branchLost) it + SessionAction.NewBranch else it }
    fun offers(action: SessionAction) = action in available
    val enabled get() = context().live && !busy && blocked != key(context().session)
    fun reset() { generation++; job?.cancel(); busy = false; notice = null; blocked = null; branchLost = false }
    fun refreshState() {
        if (busy || !context().live) return
        val current = generation
        busy = true
        job = scope.launch {
            try { val fresh = refresh(); currentCoroutineContext().ensureActive(); check(current == generation); updated(fresh); blocked = null; notice = null }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); notice = "Could not refresh the session. Try again when connected." }
            finally { if (current == generation) busy = false }
        }
    }

    fun perform(operation: SessionAction) {
        if (!enabled || !offers(operation)) return
        val original = context(); val current = generation
        busy = true; notice = null
        job = scope.launch {
            var dispatched = false
            try {
                val fresh = refresh()
                check(fresh.id == original.session?.id && context().live && context().session?.id == original.session?.id && context().taskId == original.taskId)
                updated(fresh)
                if (operation != SessionAction.NewBranch && operation !in sessionActions(fresh)) {
                    notice = "Session state changed. Review the available actions."; return@launch
                }
                val (action, payload) = when (operation) {
                    SessionAction.Interrupt -> "agent.cancel" to buildJsonObject { put("session_id", fresh.id) }
                    SessionAction.End -> "session.stop" to buildJsonObject { put("session_id", fresh.id); put("reason", "stopped from Forge") }
                    SessionAction.Start -> "session.launch" to buildJsonObject {
                        put("session_id", fresh.id); put("task_id", original.taskId); put("intent", "start_created")
                        put("activation_source", "user_action")
                    }
                    SessionAction.Resume, SessionAction.FreshStart, SessionAction.NewBranch -> "session.recover" to buildJsonObject {
                        put("task_id", original.taskId); put("session_id", fresh.id)
                        put("action", when (operation) {
                            SessionAction.Resume -> "resume"; SessionAction.FreshStart -> "fresh_start"; else -> "resume_new_branch"
                        })
                    }
                }
                dispatched = true
                val result = request(action, payload)
                currentCoroutineContext().ensureActive(); check(current == generation)
                check(result["success"]?.jsonPrimitive?.booleanOrNull != false)
                if (action != "session.stop" && action != "agent.cancel")
                    result["session_id"]?.jsonPrimitive?.contentOrNull?.let { check(it == fresh.id) }
                blocked = key(fresh)
                if (operation in setOf(SessionAction.Resume, SessionAction.NewBranch, SessionAction.FreshStart)) branchLost = false
                notice = when (result["activation_disposition"]?.jsonPrimitive?.contentOrNull) {
                    "queued" -> "Launch queued by Kandev."
                    "suppressed" -> "Launch was deferred by Kandev. Check the task's launch status."
                    else -> when (operation) {
                        SessionAction.Interrupt -> null
                        SessionAction.End -> "Agent session ended."
                        else -> null
                    }
                }
                runCatching { refresh() }.onSuccess(updated)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val code = (e as? KandevApiException)?.code
                if (dispatched && code == null) blocked = key(original.session)
                notice = when {
                    code == "branch_unrecoverable" -> { branchLost = true; "The session's branch is no longer available. Continue on a new branch, or start fresh." }
                    code == "task_archived" -> "This task is archived. Unarchive it in Kandev before resuming."
                    code == "recovery_inspection_busy" -> "Kandev is still checking the workspace. Try again in a moment."
                    dispatched -> "Action was not confirmed. Refresh the session state before explicitly retrying."
                    else -> "Could not check the session state. Try again when connected."
                }
            } finally { if (current == generation) busy = false }
        }
    }
}
