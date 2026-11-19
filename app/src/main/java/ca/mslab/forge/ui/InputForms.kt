package ca.mslab.forge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import ca.mslab.forge.ui.theme.ForgeCodeFont
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import ca.mslab.forge.ui.theme.ForgeTheme

/** The pending request, once it has stayed open past provider auto-approval (most settle within a second). */
@Composable
internal fun settledPending(model: ConnectionViewModel): PendingInput? {
    val pending = model.inputs.pending
    var settled by remember(pending?.key) { mutableStateOf(false) }
    LaunchedEffect(pending?.key) { if (pending != null) { kotlinx.coroutines.delay(2_500); settled = true } }
    return pending?.takeIf { settled }
}

/**
 * Takes the composer's place while the agent waits on you: what it is asking, at a glance, and one
 * tap to answer. Sits at the bottom of every tab, where your thumb already is.
 */
@Composable
internal fun PendingInputDock(model: ConnectionViewModel, pending: PendingInput) {
    val status = ForgeTheme.status
    val permission = pending is PendingInput.Permission
    val questions = (pending as? PendingInput.Questions)?.questions.orEmpty()
    val command = (pending as? PendingInput.Permission)?.message?.metadata?.actionDetails?.command
    val preview = when (pending) {
        is PendingInput.Permission -> command ?: pending.message.metadata?.actionDetails?.let { it.path ?: it.description }
            ?: pending.message.content
        is PendingInput.Questions -> questions.firstOrNull()?.let { it.title.ifBlank { it.prompt } }.orEmpty()
    }.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    Surface(onClick = { model.inputs.open(pending) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        enabled = !model.inputs.busy, color = status.warningContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(if (permission) Icons.Rounded.Shield else Icons.Rounded.QuestionAnswer, size = 40.dp,
                container = status.warning, content = status.warningContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(status.warning, size = 7.dp)
                    Text(when {
                        permission -> "Approval needed"
                        questions.size > 1 -> "${questions.size} questions for you"
                        else -> "The agent is asking"
                    }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = status.warning)
                }
                if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (command != null) ForgeCodeFont else null)
            }
            Button(onClick = { model.inputs.open(pending) }, enabled = !model.inputs.busy,
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = status.warning, contentColor = status.warningContainer)) {
                Text(if (permission) "Review" else "Answer")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputResponseSheet(model: ConnectionViewModel) {
    val controller = model.inputs
    val input = controller.opened ?: return
    var skipping by remember(input.key) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(if (input is PendingInput.Permission) Icons.Rounded.Shield else Icons.Rounded.QuestionAnswer,
                    container = ForgeTheme.status.warningContainer, content = ForgeTheme.status.warning)
                Column(Modifier.weight(1f)) {
                    Text(if (input is PendingInput.Permission) "Review approval" else "The agent is asking",
                        style = MaterialTheme.typography.titleLarge)
                    if (input is PendingInput.Questions && controller.resolution == null) {
                        val answered = input.questions.count { validAnswer(it, controller.answers[it.id]) }
                        Text(if (input.questions.size > 1) "$answered of ${input.questions.size} answered" else "Your answer goes straight to the agent",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = controller::close, enabled = !controller.busy) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!model.live) InfoNotice("Offline · answers stay on this device. Connect to respond.", icon = Icons.Rounded.CloudOff)
            controller.error?.let { ErrorNotice(it) }
            controller.notice?.let { InfoNotice(it) }
            if (!controller.busy && !controller.enabled && model.live && controller.notice == null)
                InfoNotice("This request changed. Close and review the latest conversation.", icon = Icons.Rounded.Update)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                when (input) {
                    is PendingInput.Permission -> {
                        item { MarkdownText(input.message.content.ifBlank { "An action needs your approval." }, Modifier.fillMaxWidth()) }
                        input.message.metadata?.actionDetails?.let { details ->
                            details.command?.let { item { DetailText("Command", it) } }
                            details.path?.let { item { DetailText("Path", it) } }
                            details.cwd?.let { item { DetailText("Working directory", it) } }
                            details.rawInput?.takeIf { details.command == null && details.path == null }?.let { item { DetailText("Action input", it.toString()) } }
                        }
                        items(input.message.metadata?.options.orEmpty(), key = { it.id }) { option ->
                            val allow = option.kind.startsWith("allow")
                            val enabled = controller.enabled && option.kind in setOf("allow_once", "allow_always", "reject_once", "reject_always")
                            val content: @Composable RowScope.() -> Unit = {
                                Icon(when (option.kind) {
                                    "allow_once" -> Icons.Rounded.Check
                                    "allow_always" -> Icons.Rounded.DoneAll
                                    "reject_always" -> Icons.Rounded.Block
                                    else -> Icons.Rounded.Close
                                }, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(option.name.ifBlank { option.kind.replace('_', ' ') })
                                    if (option.kind.endsWith("_always")) Text("Remembers this decision", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (allow) Button(onClick = { controller.permission(option) }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                                shape = MaterialTheme.shapes.small, content = content)
                            else OutlinedButton(onClick = { controller.permission(option) }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                                shape = MaterialTheme.shapes.small, content = content)
                        }
                        val options = input.message.metadata?.options.orEmpty()
                        if (options.none { it.kind.startsWith("reject_") || it.metadata?.get("codex_app_server")?.toString() == "true" })
                            item { OutlinedButton(controller::cancelPermission, enabled = controller.enabled, modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small) { Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Deny") } }
                        if (input.message.metadata?.options.isNullOrEmpty()) item { Text("No response choices were offered. Refresh the conversation.") }
                    }
                    is PendingInput.Questions -> {
                        if (!input.complete) item { Text("Questions are still arriving. Close and refresh the conversation before answering.") }
                        input.messages.firstOrNull()?.metadata?.context?.takeIf { it.isNotBlank() }?.let { item { MarkdownText(it) } }
                        if (controller.resolution == null) {
                        itemsIndexed(input.questions, key = { _, it -> it.id }) { index, question ->
                            QuestionForm(question, index, input.questions.size, controller.answers[question.id],
                                !controller.busy && controller.notice == null && !skipping,
                                onOption = { controller.answer(question, selected = it) }, onText = { controller.answer(question, text = it) })
                        }
                        if (skipping) item {
                            OutlinedTextField(controller.reason, controller::rejectReason, label = { Text("Why skip? (optional)") },
                                placeholder = { Text("Tell the agent how to proceed instead") },
                                modifier = Modifier.fillMaxWidth(), enabled = !controller.busy && controller.notice == null, maxLines = 3,
                                shape = MaterialTheme.shapes.small,
                                isError = controller.reason.codePointCount(0, controller.reason.length) > 2000)
                        }
                        }
                    }
                }
                controller.resolution?.let { result ->
                    item { MetaItem(Icons.Rounded.CheckCircle, "Saved response · ${result.status}", color = ForgeTheme.status.success) }
                    items(result.response.answers, key = { "saved:${it.questionId}" }) { answer ->
                        val question = (input as? PendingInput.Questions)?.questions?.firstOrNull { it.id == answer.questionId }
                        Text(listOfNotNull(question?.title, answer.selected.map { id -> question?.options?.firstOrNull { it.id == id }?.label ?: id }
                            .joinToString(", ").takeIf { it.isNotBlank() }, answer.text.takeIf { it.isNotBlank() }).joinToString("\n"))
                    }
                    if (result.response.reason.isNotBlank()) item { Text(result.response.reason) }
                }
            }
            if (input is PendingInput.Questions && controller.resolution == null) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val sending = @Composable {
                    if (controller.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                }
                if (skipping) {
                    TextButton({ skipping = false }, enabled = !controller.busy) { Text("Back") }
                    Button(controller::rejectQuestions, modifier = Modifier.weight(1f).height(48.dp), shape = MaterialTheme.shapes.small,
                        enabled = controller.enabled && input.complete && controller.reason.codePointCount(0, controller.reason.length) <= 2000) {
                        sending(); if (!controller.busy) Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Skip questions")
                    }
                } else {
                    TextButton({ skipping = true }, enabled = controller.enabled && input.complete) { Text("Skip") }
                    Button(controller::submitAnswers, modifier = Modifier.weight(1f).height(48.dp), shape = MaterialTheme.shapes.small,
                        enabled = controller.enabled && controller.allAnswered) {
                        sending(); if (!controller.busy) Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (input.questions.size > 1) "Send answers" else "Send answer")
                    }
                }
            }
            if (controller.error != null) TextButton(onClick = model::retryConversation, enabled = !controller.busy) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh conversation")
            }
        }
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraSmall) {
            SelectionContainer { Text(value, fontFamily = ForgeCodeFont, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(10.dp)) }
        }
    }
}

/** One question: numbered header, the prompt, options as tappable cards, then an optional free-text answer. */
@Composable
private fun QuestionForm(question: InputQuestion, index: Int, total: Int, answer: QuestionAnswer?, enabled: Boolean,
    onOption: (String) -> Unit, onText: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = validAnswer(question, answer)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (total > 1) Surface(color = if (done) ForgeTheme.status.successContainer else scheme.surfaceContainerHigh,
                contentColor = if (done) ForgeTheme.status.success else scheme.onSurfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (done) { Icon(Icons.Rounded.Check, null, Modifier.size(12.dp)); Spacer(Modifier.width(3.dp)) }
                    Text("${index + 1}/$total", style = MaterialTheme.typography.labelSmall, fontFamily = ForgeCodeFont)
                }
            }
            if (question.title.isNotBlank()) Text(question.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        MarkdownText(question.prompt, Modifier.fillMaxWidth())
        question.options.forEach { option ->
            val selected = answer?.selected?.contains(option.id) == true
            Surface(onClick = { onOption(option.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
                contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(20.dp),
                        tint = if (selected) scheme.primary else scheme.outline)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else null)
                        if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall,
                            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (question.allowCustom != false) {
            val length = answer?.text?.codePointCount(0, answer.text.length) ?: 0
            OutlinedTextField(answer?.text.orEmpty(), onText, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                placeholder = { Text(if (question.options.isEmpty()) "Your answer" else "Or write your own answer") },
                maxLines = 5, shape = MaterialTheme.shapes.medium, isError = length > 2000,
                supportingText = if (length > 1500) ({ Text("$length/2000") }) else null)
        }
    }
}
