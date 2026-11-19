package ca.mslab.forge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class SessionTurn(val id: String, @SerialName("session_id") val sessionId: String,
    @SerialName("started_at") val startedAt: String, @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null, val metadata: JsonObject? = null)
@Serializable internal data class TurnList(val turns: List<SessionTurn>)
@Serializable
data class PermissionOption(@SerialName("option_id") val id: String, val name: String, val kind: String,
    val metadata: JsonObject? = null)
@Serializable
data class PermissionDetails(val command: String? = null, val path: String? = null, val cwd: String? = null,
    val description: String? = null, @SerialName("raw_input") val rawInput: JsonObject? = null)
@Serializable
data class QuestionOption(@SerialName("option_id") val id: String, val label: String, val description: String = "")
@Serializable
data class InputQuestion(val id: String, val title: String = "", val prompt: String,
    val options: List<QuestionOption> = emptyList(), @SerialName("allow_custom_text") val allowCustom: Boolean? = null)
@Serializable
data class QuestionAnswer(@SerialName("question_id") val questionId: String,
    @SerialName("selected_options") val selected: List<String> = emptyList(), @SerialName("custom_text") val text: String = "")
@Serializable internal data class AnswerDraft(val answers: List<QuestionAnswer> = emptyList(), val reason: String = "")
@Serializable
internal data class ClarificationResolution(val success: Boolean, val claimed: Boolean, val status: String,
    val response: ClarificationResponse)
@Serializable
internal data class ClarificationResponse(@SerialName("pending_id") val pendingId: String,
    val answers: List<QuestionAnswer>, val rejected: Boolean, @SerialName("reject_reason") val reason: String = "")

internal sealed interface PendingInput {
    val key: String
    data class Permission(val message: ChatMessage) : PendingInput {
        override val key get() = "permission:${message.metadata?.pendingId}:${message.metadata?.requestId}"
    }
    data class Questions(val pendingId: String, val messages: List<ChatMessage>, val complete: Boolean) : PendingInput {
        override val key get() = "questions:$pendingId"
        val questions get() = messages.mapNotNull { it.metadata?.question }
    }
}

internal data class InputContext(val taskId: String, val session: AgentSession?, val messages: List<ChatMessage>,
    val turns: List<SessionTurn>?, val live: Boolean) {
    fun pending(): PendingInput? {
        val selected = session ?: return null
        val eligible = turns?.filter { turn ->
            turn.sessionId == selected.id && turn.metadata?.get("lifecycle_only") !in
                setOf(JsonPrimitive(true), JsonPrimitive(1), JsonPrimitive("true"), JsonPrimitive("1"))
        } ?: return null
        val turn = eligible.maxWithOrNull(compareBy<SessionTurn> { it.completedAt == null }
            .thenBy { java.time.Instant.parse(it.startedAt) }.thenBy { java.time.Instant.parse(it.createdAt) }.thenBy { it.id }) ?: return null
        if (turn.completedAt != null || selected.state !in setOf("RUNNING", "WAITING_FOR_INPUT")) return null
        val rows = messages.filter { it.sessionId == selected.id }
        val permission = rows.lastOrNull { it.type == "permission_request" && it.turnId == turn.id }
        if (turn.completedAt == null && permission?.metadata?.let { it.status == null || it.status == "pending" } == true &&
            !permission.metadata?.requestId.isNullOrBlank() && !permission.metadata?.pendingId.isNullOrBlank()) return PendingInput.Permission(permission)
        val pending = rows.lastOrNull { it.type == "clarification_request" &&
            (it.turnId == turn.id || (turn.completedAt == null && it.metadata?.agentDisconnected == true)) &&
            (it.metadata?.status == null || it.metadata.status == "pending") } ?: return null
        val id = pending.metadata?.pendingId?.takeIf { it.isNotBlank() } ?: return null
        val bundle = rows.filter { it.type == "clarification_request" && it.metadata?.pendingId == id }
            .sortedBy { it.metadata?.questionIndex ?: 0 }
        val total = bundle.maxOfOrNull { it.metadata?.questionTotal ?: 1 } ?: 1
        val active = bundle.filter { it.metadata?.status == null || it.metadata.status == "pending" }
        val questions = active.mapNotNull { it.metadata?.question }
        return PendingInput.Questions(id, active, bundle.size >= total && questions.size == active.size &&
            questions.isNotEmpty() && questions.all { it.id.isNotBlank() } && questions.map { it.id }.distinct().size == questions.size)
    }
}

internal fun permissionPayload(context: InputContext, pending: PendingInput.Permission, option: PermissionOption): JsonObject {
    val metadata = requireNotNull(pending.message.metadata)
    require(metadata.options.contains(option) && option.kind in setOf("allow_once", "allow_always", "reject_once", "reject_always"))
    val cancel = option.metadata?.get("codex_decision")?.jsonPrimitive?.contentOrNull == "cancel"
    return buildJsonObject {
        put("task_id", context.taskId); put("session_id", requireNotNull(context.session).id)
        put("request_id", metadata.requestId); put("pending_id", metadata.pendingId)
        if (!cancel) put("option_id", option.id)
        put("cancelled", cancel); put("rejected", !cancel && option.kind.startsWith("reject_"))
    }
}

internal fun validAnswer(question: InputQuestion, answer: QuestionAnswer?): Boolean = answer != null &&
    answer.questionId == question.id && answer.selected.size <= 1 &&
    answer.selected.all { id -> question.options.any { it.id == id } } &&
    (question.allowCustom != false || answer.text.isEmpty()) && answer.text.codePointCount(0, answer.text.length) <= 2000 &&
    (answer.selected.isNotEmpty() || answer.text.isNotBlank())

internal fun cancelPermissionPayload(context: InputContext, pending: PendingInput.Permission): JsonObject = buildJsonObject {
    val metadata = requireNotNull(pending.message.metadata)
    put("task_id", context.taskId); put("session_id", requireNotNull(context.session).id)
    put("request_id", metadata.requestId); put("pending_id", metadata.pendingId)
    put("cancelled", true); put("rejected", false)
}
