package ca.mslab.forge.data

import androidx.compose.runtime.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class QueuedMessage(val id: String, val content: String = "", val position: Int = 0,
    @SerialName("queued_at") val queuedAt: String = "",
    val attachments: List<JsonObject>? = null)

@Serializable
internal data class QueueStatus(val entries: List<QueuedMessage> = emptyList(), val count: Int = 0, val max: Int = 10,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_incarnation_id") val incarnationId: String = "",
    @SerialName("auto_run") val autoRun: Boolean = true)

/** How a draft leaves the composer for this session right now. */
internal enum class InputMode { Direct, Steer, Queue, Unavailable }

/**
 * Same rule as the web composer: idle sessions take messages directly; a generating turn queues, unless the
 * agent can take a message mid-turn (steering) and nothing is queued ahead of it, or the turn is only held
 * open by background work. Ended sessions take nothing; they come back through recovery first.
 */
internal fun inputMode(session: AgentSession?, queued: Int): InputMode = when {
    session == null -> InputMode.Unavailable
    session.state in setOf("CREATED", "IDLE", "WAITING_FOR_INPUT") -> InputMode.Direct
    session.state == "STARTING" -> InputMode.Queue
    session.state != "RUNNING" -> InputMode.Unavailable
    session.activity == "background" -> InputMode.Direct
    session.supportsSteering && queued == 0 -> InputMode.Steer
    else -> InputMode.Queue
}

internal data class QueueContext(val taskId: String, val session: AgentSession?, val live: Boolean)

/**
 * The session's follow-up queue: what is waiting, and the operations the web offers on it. Every mutation
 * is one explicit request bound to the session incarnation; nothing is replayed after a reconnect.
 * Send now interrupts the running turn and delivers the chosen message in its place (the web's steer).
 */
internal class QueueController(
    private val scope: CoroutineScope,
    private val context: () -> QueueContext,
    private val request: suspend (String, JsonObject, Long) -> JsonObject,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    var status by mutableStateOf(QueueStatus()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Entry ids with a request in flight, so their row shows progress and can't be tapped twice. */
    var working by mutableStateOf(emptySet<String>()); private set
    private var generation = 0
    private var sessionId: String? = null

    val count get() = status.count
    val full get() = status.count >= status.max

    fun selectSession(id: String?) {
        if (id == sessionId) return
        generation++; sessionId = id; status = QueueStatus(); error = null; working = emptySet(); busy = false
    }
    fun clearError() { error = null }

    private fun identity(): JsonObject? {
        val ctx = context(); val session = ctx.session ?: return null
        if (session.queueIncarnationId.isBlank() || ctx.taskId.isBlank()) return null
        return buildJsonObject {
            put("task_id", ctx.taskId); put("session_id", session.id); put("session_incarnation_id", session.queueIncarnationId)
        }
    }

    /** `message.queue.status_changed` carries the full status; apply it when it is for this session. */
    fun onStatus(payload: JsonObject) {
        if (payload["session_id"]?.jsonPrimitive?.contentOrNull != sessionId) return
        runCatching { json.decodeFromJsonElement(QueueStatus.serializer(), payload) }.getOrNull()?.let { status = it }
    }

    suspend fun refresh() {
        val ids = identity() ?: return
        if (!context().live) return
        val current = generation
        runCatching { request("message.queue.get", ids, 10_000) }.getOrNull()?.let { response ->
            if (current == generation) runCatching { json.decodeFromJsonElement(QueueStatus.serializer(), response) }.getOrNull()?.let { status = it }
        }
    }

    /** Adds a follow-up. Returns true once Kandev confirmed it, so the caller can clear the draft. */
    suspend fun add(content: String): Boolean {
        val ids = identity() ?: return false
        error = null
        return try {
            request("message.queue.add", JsonObject(ids + buildJsonObject {
                put("content", content); put("client_queue_id", UUID.randomUUID().toString())
            }), 10_000)
            refresh(); true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            error = when ((e as? KandevApiException)?.code) {
                "queue_full" -> "The queue is full (${status.max}). Wait for the next turn, or send one now."
                "session_not_promptable" -> "This session can't take messages right now."
                else -> "Not queued. Check the queue before retrying; your draft is kept."
            }
            false
        }
    }

    /** Interrupts the current turn and sends this entry (or the whole queue, oldest first) in its place. */
    fun sendNow(entryId: String?) = mutate(entryId ?: "all", "message.queue.send_now", buildJsonObject {
        if (entryId == null) put("scope", "all") else { put("scope", "entry"); put("entry_id", entryId) }
    })

    fun remove(entryId: String) = mutate(entryId, "message.queue.remove", buildJsonObject { put("entry_id", entryId) })

    fun clear() = mutate("all", "message.queue.cancel", buildJsonObject { })

    /** Moves an entry one place up or down. */
    fun move(entryId: String, delta: Int) {
        val ids = status.entries.sortedBy { it.position }.map { it.id }.toMutableList()
        val from = ids.indexOf(entryId); val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        mutate(entryId, "message.queue.reorder", buildJsonObject { put("ordered_ids", JsonArray(ids.map(::JsonPrimitive))) })
    }

    /** Replaces a queued entry's text through Kandev's edit lease, so a concurrent drain can't lose it. */
    fun edit(entryId: String, content: String) {
        val ids = identity() ?: return
        val session = ids.getValue("session_id").jsonPrimitive.content
        if (!context().live || entryId in working || content.isBlank()) return
        val current = generation
        working = working + entryId; error = null
        scope.launch {
            var lease: String? = null
            try {
                val begun = request("message.queue.edit.begin", buildJsonObject { put("session_id", session); put("entry_id", entryId) }, 10_000)
                lease = begun.getValue("lease_id").jsonPrimitive.content
                request("message.queue.update", JsonObject(ids + buildJsonObject {
                    put("entry_id", entryId); put("lease_id", lease); put("operation_id", UUID.randomUUID().toString())
                    put("expected_target_revision", begun.getValue("target_revision").jsonPrimitive.long); put("content", content.trim())
                    put("entity_references", JsonArray(emptyList()))
                }), 10_000)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) error = failure(e, "Not saved. The entry may have been sent already.")
            } finally {
                lease?.let { id -> runCatching { request("message.queue.edit.end", buildJsonObject {
                    put("session_id", session); put("entry_id", entryId); put("lease_id", id)
                }, 10_000) } }
                if (current == generation) { working = working - entryId; refresh() }
            }
        }
    }

    private fun mutate(key: String, action: String, extra: JsonObject) {
        val ids = identity() ?: return
        if (!context().live || key in working) return
        val current = generation
        working = working + key; error = null
        scope.launch {
            try { request(action, JsonObject(ids + extra), 15_000) }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (current == generation) error = failure(e, "Kandev didn't confirm that. Check the queue before retrying.")
            } finally { if (current == generation) { working = working - key; refresh() } }
        }
    }

    private fun failure(e: Exception, fallback: String) = when ((e as? KandevApiException)?.code) {
        "entry_not_found" -> "That message already left the queue."
        "edit_conflict", "queue_conflict" -> "The entry changed while you were editing. Review it and try again."
        "queue_changed", "send_now_conflict", "turn_changed" -> "The queue or turn changed. Review it and try again."
        "queue_empty" -> "Nothing is queued."
        "session_busy", "session_not_promptable" -> "The session can't take that right now."
        else -> fallback
    }
}
