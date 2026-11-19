package ca.mslab.forge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.AgentProfile
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.StepRow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import ca.mslab.forge.data.Executor
import ca.mslab.forge.data.sync.epochMillis
import ca.mslab.forge.ui.theme.ForgeCodeFont

/** Agent profiles flattened for pickers, labelled with their agent ("Opus 5.5 · claude"). */
internal data class ProfileChoice(val profile: AgentProfile, val agent: String)

private fun ConnectionViewModel.profileChoices(): List<ProfileChoice> = launchOptions?.agents.orEmpty()
    .flatMap { agent -> agent.profiles.orEmpty().map { ProfileChoice(it, agent.name.removeSuffix("-acp")) } }

private fun ConnectionViewModel.executorChoices(): List<Executor> = launchOptions?.executors.orEmpty().filter { it.status != "disabled" }

/** Defaults follow your most recent work: same repository, agent profile and executor. */
private fun ConnectionViewModel.recentTask() = tasks.maxByOrNull { epochMillis(it.lastActivityAt) }

/** Write the prompt, keep the defaults, tap Create: the agent starts and the task opens. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewTaskSheet(model: ConnectionViewModel, onDismiss: () -> Unit) {
    val recent = remember(model.tasks) { model.recentTask() }
    val repositories = remember(model.repositories) { model.repositories.values.sortedBy { it.name.lowercase() } }
    var prompt by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var repositoryId by rememberSaveable { mutableStateOf(recent?.repositories?.firstOrNull()?.repositoryId ?: repositories.firstOrNull()?.id) }
    val repository = repositoryId?.let { model.repositories[it] }
    var branch by rememberSaveable(repositoryId) {
        mutableStateOf(recent?.repositories?.firstOrNull { it.repositoryId == repositoryId }?.baseBranch ?: repository?.defaultBranch.orEmpty())
    }
    val profiles = model.profileChoices()
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val defaultProfile = profiles.firstOrNull { it.profile.name == recent?.agentName }?.profile?.id
        ?: model.workspace?.defaultAgentProfileId ?: profiles.firstOrNull()?.profile?.id
    val chosenProfile = profileId ?: defaultProfile
    val executors = model.executorChoices()
    var executorId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenExecutor = executorId ?: executors.firstOrNull { it.name == recent?.executorName }?.id ?: model.workspace?.defaultExecutorId
    var planMode by rememberSaveable { mutableStateOf(false) }
    var startAgent by rememberSaveable { mutableStateOf(true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::addDocuments)
    DisposableEffect(Unit) { model.beginTaskDraft(); onDispose { model.endTaskDraft() } }

    ModalBottomSheet(onDismissRequest = { if (!model.creating) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New task", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(model.workspace?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                placeholder = { Text("What should the agent do?") }, enabled = !model.creating,
                textStyle = MaterialTheme.typography.bodyMedium)
            // Screenshots, logs or specs the agent should see, sent with the first prompt.
            AttachmentComposer(model)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(Icons.Rounded.Source, repository?.name ?: "No repository",
                    listOf<Pair<String?, String>>(null to "No repository") + repositories.map { it.id to it.name }) { repositoryId = it }
                Picker(Icons.Rounded.AutoAwesome, profiles.firstOrNull { it.profile.id == chosenProfile }?.profile?.name ?: "Agent",
                    profiles.map { it.profile.id to "${it.profile.name} · ${it.agent}" },
                    lead = { AgentMark(it) }, selected = chosenProfile) { profileId = it }
                Picker(Icons.Rounded.Computer, executors.firstOrNull { it.id == chosenExecutor }?.name ?: "Executor",
                    executors.map { it.id to it.name }) { executorId = it }
                FilterChip(planMode, { planMode = !planMode }, label = { Text("Plan first") },
                    leadingIcon = { Icon(Icons.Rounded.Checklist, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { pickFiles.launch(arrayOf("*/*")) },
                    enabled = !model.creating && !model.attachments.importing && model.attachments.files.size < ca.mslab.forge.data.MAX_ATTACHMENTS,
                    label = { Text(if (model.attachments.files.isEmpty()) "Attach" else "Attach · ${model.attachments.files.size}") },
                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(16.dp)) })
            }
            if (profiles.isEmpty()) InfoNotice("Loading agents… connect to Kandev to choose one.", icon = Icons.Rounded.CloudSync)
            TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(if (advanced) "Fewer options" else "Title, branch, start")
            }
            if (advanced) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !model.creating,
                    label = { Text("Title") }, placeholder = { Text("Generated from the prompt") })
                if (repository != null) OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), singleLine = true,
                    enabled = !model.creating, label = { Text("Base branch") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.AltRoute, null) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ForgeCodeFont))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start the agent now", style = MaterialTheme.typography.bodyMedium)
                        Text(if (startAgent) "Runs immediately" else "Creates the task in its first step", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(startAgent, { startAgent = it }, enabled = !model.creating)
                }
            }
            model.createError?.let { ErrorNotice(it) }
            Button(onClick = {
                model.createTask(prompt, title, repository, branch, chosenProfile, chosenExecutor, planMode, startAgent)
            }, enabled = !model.creating && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (model.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else {
                    Icon(if (startAgent) Icons.Rounded.PlayArrow else Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (startAgent) "Create and start" else "Create task")
                }
            }
        }
    }
    AttachmentPreviewSheet(model)
    // Close once the new task is open.
    LaunchedEffect(model.task?.id) { if (model.task != null && !model.creating) onDismiss() }
}

/** A fresh agent session on the open task: pick the agent, write the first prompt. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewSessionSheet(model: ConnectionViewModel, onDismiss: () -> Unit) {
    val profiles = model.profileChoices()
    var prompt by rememberSaveable { mutableStateOf("") }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = profileId ?: profiles.firstOrNull { it.profile.name == model.task?.agentName }?.profile?.id ?: profiles.firstOrNull()?.profile?.id
    val executors = model.executorChoices()
    var executorId by rememberSaveable { mutableStateOf<String?>(null) }
    val before = remember { model.session?.id }
    ModalBottomSheet(onDismissRequest = { if (!model.creating) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
            .padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            Text(model.task?.title.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !model.creating,
                placeholder = { Text("First message for the new session") }, textStyle = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(Icons.Rounded.AutoAwesome, profiles.firstOrNull { it.profile.id == chosen }?.profile?.name ?: "Agent",
                    profiles.map { it.profile.id to "${it.profile.name} · ${it.agent}" },
                    lead = { AgentMark(it) }, selected = chosen) { profileId = it }
                if (executors.isNotEmpty()) Picker(Icons.Rounded.Computer, executors.firstOrNull { it.id == executorId }?.name ?: "Same executor",
                    listOf<Pair<String?, String>>(null to "Same executor") + executors.map { it.id to it.name }) { executorId = it }
            }
            model.createError?.let { ErrorNotice(it) }
            Button(onClick = { chosen?.let { model.startNewSession(prompt, it, executorId) } },
                enabled = !model.creating && prompt.isNotBlank() && chosen != null && model.live, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (model.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Start session") }
            }
            if (!model.live) InfoNotice("Connect to Kandev to start a session.", icon = Icons.Rounded.CloudOff)
        }
    }
    LaunchedEffect(model.session?.id) { if (model.session?.id != null && model.session?.id != before && !model.creating) onDismiss() }
}

/** Compact dropdown chip. */
@Composable
private fun <T> Picker(icon: ImageVector, label: String, options: List<Pair<T, String>>,
    lead: (@Composable (T?) -> Unit)? = null, selected: T? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { if (lead != null) lead(selected) else Icon(icon, null, Modifier.size(16.dp)) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) },
                leadingIcon = lead?.let { { it(value) } }) }
        }
    }
}


/** Pick a step, see what entering it does (agent run, context reset), confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveTaskSheet(model: ConnectionViewModel, task: TaskSummary, initial: StepRow? = null, onDismiss: () -> Unit) {
    val steps = remember(model.steps, task.workflowId) { model.steps.filter { it.workflowId == task.workflowId }.sortedBy { it.position } }
    var target by remember { mutableStateOf(initial) }
    var archive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.clearMove(); initial?.let { model.previewMove(task, it) } }
    ModalBottomSheet(onDismissRequest = { if (!model.moveBusy) { model.clearMove(); onDismiss() } }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
            .padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Move task", style = MaterialTheme.typography.titleLarge)
            Text(task.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            steps.forEach { step ->
                val current = step.id == task.stepId
                val selected = step.id == target?.id
                Surface(onClick = { if (!current) { target = step; model.previewMove(task, step) } }, enabled = !current && !model.moveBusy,
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stepColor(step.color) ?: MaterialTheme.colorScheme.outline))
                        Text(step.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        when {
                            current -> Text("Current", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            step.completesTask -> Text("Completes task", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val preview = model.movePreview
            when {
                model.moveError != null -> ErrorNotice(model.moveError!!)
                target != null && preview == null && model.moveBusy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Text("Checking what this move does…", style = MaterialTheme.typography.labelMedium)
                }
                preview != null -> {
                    val runs = preview.dispatch.isNotBlank() && preview.dispatch != "no_prompt"
                    InfoNotice(buildString {
                        append(if (runs) "Starts ${preview.recipient?.profileName ?: "the agent"} with this step's prompt." else "Won't run the agent.")
                        if (preview.contextReset) append(" The agent's context resets.")
                    }, icon = if (runs) Icons.Rounded.PlayCircle else Icons.Rounded.Info)
                }
            }
            Button(onClick = { target?.let { model.moveTask(task, it) { onDismiss() } } },
                enabled = target != null && model.movePreview != null && !model.moveBusy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (model.moveBusy && model.movePreview != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(target?.let { "Move to ${it.name}" } ?: "Choose a step")
            }
            TextButton(onClick = { archive = true }, enabled = !model.moveBusy && !model.housekeepingBusy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Archive task")
            }
        }
    }
    if (archive) ArchiveTaskDialog(model, task, onArchived = onDismiss) { archive = false }
}
