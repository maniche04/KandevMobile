package ca.mslab.forge.data

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit responses only. A reconnect never submits an answer or permission. */
internal class InputController(
    private val scope: CoroutineScope,
    private val context: () -> InputContext,
    private val revalidate: suspend () -> InputContext,
    private val permissionRequest: suspend (JsonObject) -> Unit,
    private val questionRequest: suspend (String, JsonObject) -> ClarificationResolution,
    private val store: () -> SnapshotStore?,
    private val resolved: (PendingInput, String, List<QuestionAnswer>) -> Unit,
) {
    var opened by mutableStateOf<PendingInput?>(null); private set
    var answers by mutableStateOf<Map<String, QuestionAnswer>>(emptyMap()); private set
    var reason by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var resolution by mutableStateOf<ClarificationResolution?>(null); private set
    private var acknowledged by mutableStateOf<String?>(null)
    private var unavailable by mutableStateOf<String?>(null)
    private var generation = 0
    private var action: Job? = null
    private var draftWrite: Job? = null
    private val writes = mutableSetOf<Job>()
    private var storageKey: String? = null
    private var storage: SnapshotStore? = null
    val pending get() = context().pending()?.takeUnless { it.key == acknowledged || it.key == unavailable }
    val enabled get() = !busy && context().live && opened?.key == pending?.key
    val allAnswered get() = (opened as? PendingInput.Questions)?.let { group ->
        group.complete && group.questions.all { validAnswer(it, answers[it.id]) }
    } == true

    fun open(input: PendingInput) {
        if (busy) return
        if (opened?.key == input.key) { opened = input; return }
        persistDraft(); draftWrite?.cancel()
        val current = ++generation
        opened = input; answers = emptyMap(); reason = ""; error = null; notice = null; resolution = null
        storage = store(); storageKey = "input:${context().session?.id}:${input.key}"
        val target = storage; val key = storageKey ?: return
        busy = true
        action = scope.launch {
            try {
                target?.read(key, AnswerDraft.serializer())?.value?.let { saved ->
                    answers = saved.answers.associateBy { it.questionId }; reason = saved.reason
                }
            } catch (e: Exception) { currentCoroutineContext().ensureActive(); error = "Saved answers could not be restored." }
            finally { if (generation == current) busy = false }
        }
    }
    fun close() { if (!busy) { persistDraft(); opened = null } }
    fun reset() {
        generation++; persistDraft(); action?.cancel(); draftWrite?.cancel()
        opened = null; answers = emptyMap(); reason = ""; busy = false; error = null; notice = null
        acknowledged = null; unavailable = null; resolution = null; storage = null; storageKey = null
    }
    fun answer(question: InputQuestion, selected: String? = null, text: String = "") {
        if (busy) return
        answers = answers + (question.id to QuestionAnswer(question.id, selected?.let(::listOf).orEmpty(), text))
        scheduleDraft()
    }
    fun rejectReason(value: String) { if (!busy) { reason = value; scheduleDraft() } }
    private fun scheduleDraft() { draftWrite?.cancel(); draftWrite = scope.launch { delay(250); persistDraft() } }
    private fun persistDraft() {
        val target = storage ?: return
        val key = storageKey ?: return
        val value = AnswerDraft(answers.values.toList(), reason)
        val job = scope.launch { runCatching { target.write(key, AnswerDraft.serializer(), value) } }
        writes.add(job); job.invokeOnCompletion { writes.remove(job) }
    }

    fun permission(option: PermissionOption) = submit(option, false)
    fun cancelPermission() = submit(null, true)
    fun submitAnswers() = submit(null, false)
    fun rejectQuestions() = submit(null, true)

    private fun submit(option: PermissionOption?, reject: Boolean) {
        val input = opened ?: return
        if (!enabled) return
        val original = context()
        val currentGeneration = generation
        val submitted = answers.toMap()
        val rejection = reason.trim()
        if (input is PendingInput.Questions && (!input.complete || (!reject && !allAnswered) ||
            rejection.codePointCount(0, rejection.length) > 2000)) return
        if (input is PendingInput.Permission && option == null && !reject) return
        busy = true; error = null; notice = null
        persistDraft()
        action = scope.launch {
            try {
                val fresh = revalidate()
                check(fresh.live && original.session?.id == fresh.session?.id && original.taskId == fresh.taskId)
                val current = fresh.pending()
                if (current?.key != input.key || !sameRequest(input, current)) {
                    if (current?.key != input.key) unavailable = input.key
                    notice = "This request changed or was answered elsewhere. Close and review the latest conversation."
                    return@launch
                }
                when (current) {
                    is PendingInput.Permission -> {
                        permissionRequest(if (option == null) cancelPermissionPayload(fresh, current) else permissionPayload(fresh, current, option))
                        currentCoroutineContext().ensureActive()
                        check(generation == currentGeneration && context().session?.id == original.session?.id)
                        acknowledged = input.key
                        val cancelled = option == null || option.metadata?.get("codex_decision")?.jsonPrimitive?.contentOrNull == "cancel"
                        resolved(current, if (cancelled) "expired" else if (option?.kind?.startsWith("reject_") == true) "rejected" else "approved", emptyList())
                        // Confirmed by the server: nothing left to review, so the sheet closes itself.
                        draftWrite?.cancel(); writes.toList().joinAll()
                        storageKey?.let { storage?.remove(it) }; storageKey = null
                        opened = null
                    }
                    is PendingInput.Questions -> {
                        val body = buildJsonObject {
                            put("rejected", reject)
                            if (reject) put("reject_reason", rejection)
                            else put("answers", Json.encodeToJsonElement(current.questions.map { requireNotNull(submitted[it.id]) }))
                        }
                        val result = questionRequest(current.pendingId, body)
                        currentCoroutineContext().ensureActive()
                        check(generation == currentGeneration && context().session?.id == original.session?.id)
                        check(result.success && result.response.pendingId == current.pendingId && result.status in setOf("answered", "rejected"))
                        check(result.response.rejected == (result.status == "rejected"))
                        if (result.status == "answered") check(result.response.answers.map { it.questionId }.toSet() == current.questions.map { it.id }.toSet() &&
                            result.response.answers.map { it.questionId }.distinct().size == result.response.answers.size)
                        acknowledged = input.key; resolution = result
                        resolved(current, result.status, result.response.answers)
                        if (result.claimed) {
                            draftWrite?.cancel(); writes.toList().joinAll()
                            storageKey?.let { storage?.remove(it) }; storageKey = null
                            opened = null
                        } else notice = "Another client answered first. The saved response is shown below."
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is KandevApiException && e.code in setOf("not_active", "permission_not_found", "permission_stale", "permission_already_resolved")) {
                    unavailable = input.key; notice = "This request is no longer available. Refresh the conversation."
                } else error = "Response was not confirmed. Your answers are kept. Refresh the conversation before explicitly retrying."
            } finally { if (generation == currentGeneration) busy = false }
        }
    }
}

private fun sameRequest(old: PendingInput, fresh: PendingInput): Boolean = when {
    old is PendingInput.Permission && fresh is PendingInput.Permission -> old.message.id == fresh.message.id &&
        old.message.content == fresh.message.content && old.message.metadata?.options == fresh.message.metadata?.options &&
        old.message.metadata?.actionDetails == fresh.message.metadata?.actionDetails
    old is PendingInput.Questions && fresh is PendingInput.Questions -> fresh.complete && old.questions == fresh.questions
    else -> false
}
