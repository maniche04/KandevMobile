package ca.mslab.forge.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ca.mslab.forge.data.ChatMessage
import ca.mslab.forge.data.approvalsByToolCall
import ca.mslab.forge.data.isAutoApproved
import ca.mslab.forge.data.isOpenPermission
import ca.mslab.forge.data.isImagePath
import ca.mslab.forge.data.isOpenableFilePath
import ca.mslab.forge.data.toolFilePath
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import ca.mslab.forge.data.visibleMessages
import ca.mslab.forge.data.SessionAction
import ca.mslab.forge.data.ENDED_STATES
import ca.mslab.forge.data.InputMode
import ca.mslab.forge.data.db.RuntimeRow
import androidx.compose.ui.input.key.*
import ca.mslab.forge.data.sync.TodoItem
import ca.mslab.forge.data.sync.storeJson
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import ca.mslab.forge.data.MAX_ATTACHMENTS
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.WorkspaceTab
import ca.mslab.forge.data.conversationGroups
import ca.mslab.forge.data.imageReadPath
import ca.mslab.forge.data.isToolMessage
import ca.mslab.forge.data.outgoingAnchor
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import java.time.Instant

/** Chat scroll position and expanded groups; held above the tabs so a trip to Files keeps your place. */
@Stable
internal class ChatViewState {
    val list = LazyListState()
    var expandedGroups by mutableStateOf(emptySet<String>())
    var following by mutableStateOf(true)
}

@Composable
internal fun TaskConversationScreen(model: ConnectionViewModel) {
    val tools = model.tools
    val session = model.session
    // Back walks out of a panel first (open file, then the panel) before it leaves the task.
    BackHandler(enabled = session != null && tools.tab != WorkspaceTab.Chat) { tools.back() }
    val chat = remember(session?.id) { ChatViewState() }
    val pending = settledPending(model)
    Column(Modifier.fillMaxSize()) {
        TaskTopBar(model)
        SessionStrip(model)
        // Cached sessions paint at once; only show progress when there is nothing to show yet.
        if (model.busy && model.sessions.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        model.error?.let { ErrorNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        model.sessionActions.notice?.let { InfoNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        model.housekeepingError?.let { ErrorNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (session == null) {
                if (!model.busy) EmptyState(Icons.Rounded.Forum, "No session yet",
                    "Start a session for this task in Kandev, then reload.") {
                    FilledTonalButton(onClick = { model.task?.let(model::openTask) }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reload sessions")
                    }
                }
            } else if (tools.tab == WorkspaceTab.Chat) {
                ReadingColumn { ConversationContent(model, chat, composer = pending == null) }
            } else WorkspacePanel(tools)
        }
        // The agent is waiting on you: the request takes the composer's place, on every tab.
        var shown by remember { mutableStateOf(pending) }
        if (pending != null) shown = pending
        AnimatedVisibility(pending != null && session != null) { shown?.let { ReadingColumn(Modifier.wrapContentHeight()) { PendingInputDock(model, it) } } }
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        if (!keyboardVisible) TaskTabs(model)
    }
    InputResponseSheet(model)
    AttachmentPreviewSheet(model)
}

/** On wide screens the conversation keeps a readable line length, centred; on phones it is simply full width. */
@Composable
internal fun ReadingColumn(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 880.dp)) { content() }
    }
}

/** Compact bottom tabs; Changes carries the number of changed files. */
@Composable
private fun TaskTabs(model: ConnectionViewModel) {
    val tools = model.tools
    val changed = model.task?.summary?.git?.changedFiles ?: 0
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceTab.entries.forEach { tab ->
                    val selected = tools.tab == tab
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !tools.fileSaving) { tools.selectTab(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        BadgedBox(badge = { if (tab == WorkspaceTab.Changes && changed > 0) Badge { Text(compactCount(changed)) } }) {
                            Icon(tabIcon(tab, selected), null, Modifier.size(22.dp), tint = color)
                        }
                        Text(tab.title, style = MaterialTheme.typography.labelSmall, color = color,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: WorkspaceTab, selected: Boolean): ImageVector = when (tab) {
    WorkspaceTab.Chat -> if (selected) Icons.Rounded.Forum else Icons.Rounded.ChatBubbleOutline
    WorkspaceTab.Plan -> Icons.Rounded.Checklist
    WorkspaceTab.Changes -> Icons.Rounded.Difference
    WorkspaceTab.Files -> if (selected) Icons.Rounded.Folder else Icons.Rounded.FolderOpen
    WorkspaceTab.Terminal -> Icons.Rounded.Terminal
}

@Composable
private fun TaskTopBar(model: ConnectionViewModel) {
    var switcher by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var endConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<ca.mslab.forge.data.AgentSession?>(null) }
    val context = LocalContext.current
    val task = model.task
    val repository = task?.repositories?.firstOrNull()
    val repoName = repository?.let { model.repositories[it.repositoryId]?.name }
    val branch = model.session?.baseBranch?.takeIf { it.isNotBlank() } ?: repository?.baseBranch
    val git = task?.summary?.git
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { switcher = true }.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ExpandMore, "Switch task", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = listOfNotNull(repoName, branch?.let { "⎇ $it" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), fontFamily = ForgeCodeFont)
                if (git != null && (git.additions > 0 || git.deletions > 0)) DiffStat(git.additions, git.deletions)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reconnect") }, leadingIcon = { Icon(Icons.Rounded.Sync, null) },
                    enabled = !model.sending, onClick = { menu = false; model.retryConversation() })
                DropdownMenuItem(text = { Text("Refresh session state") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                    enabled = model.live && !model.sessionActions.busy, onClick = { menu = false; model.sessionActions.refreshState() })
                // Ending the agent process is deliberate, so it lives here; Stop by the composer only interrupts the turn.
                if (model.sessionActions.offers(SessionAction.End)) DropdownMenuItem(text = { Text("End agent session") },
                    leadingIcon = { Icon(Icons.Rounded.StopCircle, null) }, enabled = model.sessionActions.enabled,
                    onClick = { menu = false; endConfirm = true })
                model.session?.takeIf { it.state !in setOf("RUNNING", "STARTING") && model.sessions.size > 1 }?.let { selected ->
                    DropdownMenuItem(text = { Text("Delete this session") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        enabled = model.live && !model.housekeepingBusy, onClick = { menu = false; deleteConfirm = selected })
                }
                if (task != null) {
                    DropdownMenuItem(text = { Text("Open in browser") }, leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
                        onClick = {
                            menu = false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.serverUrl.trimEnd('/') + "/t/" + task.id))) }
                        })
                    DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(Icons.Rounded.MoveDown, null) },
                        enabled = model.steps.any { it.workflowId == task.workflowId },
                        onClick = { menu = false; moveSheet = true })
                    DropdownMenuItem(text = { Text("Copy task ID") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = {
                            menu = false
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Task ID", task.id))
                        })
                    DropdownMenuItem(text = { Text("Archive task") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                        enabled = !model.housekeepingBusy, onClick = { menu = false; archiveConfirm = true })
                }
            }
        }
    }
    if (switcher) TaskSwitcherSheet(model) { switcher = false }
    if (moveSheet && task != null) MoveTaskSheet(model, task) { moveSheet = false }
    if (archiveConfirm && task != null) ArchiveTaskDialog(model, task) { archiveConfirm = false }
    if (endConfirm) ConfirmDialog("End the agent session?",
        "The agent process stops and the session ends. You can resume it later from the conversation.", "End session",
        onConfirm = { endConfirm = false; model.sessionActions.perform(SessionAction.End) }) { endConfirm = false }
    deleteConfirm?.let { target ->
        ConfirmDialog("Delete this session?", "Its conversation is removed from Kandev. This can't be undone.", "Delete",
            onConfirm = { deleteConfirm = null; model.deleteSession(target) }) { deleteConfirm = null }
    }
}

/** A plain confirm/cancel dialog for the few destructive actions. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Archive from the task screen or a long-press on home; the task leaves the board once Kandev confirms. */
@Composable
internal fun ArchiveTaskDialog(model: ConnectionViewModel, task: TaskSummary, onArchived: () -> Unit = {}, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!model.housekeepingBusy) { model.clearHousekeepingError(); onDismiss() } },
        title = { Text("Archive this task?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${task.title}” leaves the board and its running agents stop. You can unarchive it in Kandev.")
                model.housekeepingError?.let { ErrorNotice(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.archiveTask(task) { model.clearHousekeepingError(); onDismiss(); onArchived() } }, enabled = !model.housekeepingBusy) {
                if (model.housekeepingBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Archive")
            }
        },
        dismissButton = { TextButton(onClick = { model.clearHousekeepingError(); onDismiss() }, enabled = !model.housekeepingBusy) { Text("Cancel") } })
}

/** Jump between tasks without going back: what needs you and what is running first, then recent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSwitcherSheet(model: ConnectionViewModel, onDismiss: () -> Unit) {
    val candidates = remember(model.tasks) {
        model.tasks.sortedWith(compareByDescending<TaskSummary> { it.needsYou() }
            .thenByDescending { it.summary?.running == true }
            .thenByDescending { ca.mslab.forge.data.sync.epochMillis(it.lastActivityAt) }).take(25)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Switch task", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(candidates, key = { it.id }) { task ->
                val repository = task.repositories.firstOrNull()
                Box(Modifier.background(if (task.id == model.task?.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)) {
                    TaskRowItem(task, 0, repository?.let { model.repositories[it.repositoryId]?.name }, repository?.baseBranch,
                        task.primarySessionId?.let { model.runtimes[it] }) {
                        onDismiss(); if (task.id != model.task?.id) model.openTask(task)
                    }
                }
            }
        }
    }
}

/** One slim row: agent and session, live state, context use, and the lifecycle action. */
@Composable
private fun SessionStrip(model: ConnectionViewModel) {
    val status = ForgeTheme.status
    val session = model.session
    val controller = model.sessionActions
    var menu by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    val runtime = session?.id?.let { model.runtimes[it] }
    val open = remember(model.steps) { openSteps(model.steps) }
    // Same settle delay as the request dock: a request that auto-approves within a moment never flashes "Needs approval".
    val agent = sessionStatus(model.task, session, open, settledPending(model))
    val look = statusLook(agent)
    // Only announce a lost connection once it has lasted; switching screens never flashes it.
    var stale by remember { mutableStateOf(false) }
    LaunchedEffect(model.live) { if (model.live) stale = false else { kotlinx.coroutines.delay(2_500); stale = true } }
    val (dot, label) = when {
        stale -> status.warning to (model.liveStatus.takeIf { it != "Live" } ?: "Offline") + if (model.messages.isNotEmpty()) " · saved" else ""
        session?.cancellationPending == true -> status.warning to "Stopping…"
        agent == AgentStatus.Running -> look.color to "Working"
        agent == AgentStatus.Background -> look.color to "Background work"
        else -> look.color to agent.label
    }
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Row(Modifier.clip(MaterialTheme.shapes.small).clickable(enabled = !model.sending) { menu = true }
                .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AgentMark(session?.agentProfileId?.ifBlank { null } ?: model.task?.agentProfileId)
                Text(model.task?.agentName?.takeIf { it.isNotBlank() } ?: "Agent", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (model.sessions.size > 1) {
                    val index = model.sessions.indexOfFirst { it.id == session?.id } + 1
                    Text("$index/${model.sessions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                Icon(Icons.Rounded.ArrowDropDown, "Sessions", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                model.sessions.forEachIndexed { index, item ->
                    val itemVisual = stateVisual(item.state)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(item.name.ifBlank { "Session ${index + 1}" } + if (item.primary) " · primary" else "",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(listOf(itemVisual.label, relativeTime(item.updatedAt.ifBlank { item.startedAt })).filter { it.isNotBlank() }
                                    .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingIcon = {
                            BadgedBox(badge = { Box(Modifier.size(7.dp).clip(CircleShape).background(itemVisual.color)) }) {
                                AgentMark(item.agentProfileId.ifBlank { null } ?: model.task?.agentProfileId, size = 20.dp)
                            }
                        },
                        trailingIcon = { if (session?.id == item.id) Icon(Icons.Rounded.Check, "Current", tint = MaterialTheme.colorScheme.primary) },
                        onClick = { menu = false; if (session?.id != item.id) model.openSession(item) })
                }
                if (model.sessions.isNotEmpty()) HorizontalDivider()
                DropdownMenuItem(text = { Text("New session") }, leadingIcon = { Icon(Icons.Rounded.Add, null) },
                    enabled = model.live, onClick = { menu = false; model.clearCreateError(); model.loadLaunchOptions(); newSession = true })
            }
        }
        if (newSession) NewSessionSheet(model) { newSession = false }
        Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(enabled = stale && !model.sending) { model.retryConversation() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = dot.copy(alpha = 0.14f), contentColor = dot, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (stale || agent.working) PulseDot(dot, size = 7.dp)
                    else Icon(look.icon, null, Modifier.size(13.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (runtime != null && runtime.contextSize > 0) {
            val used = (runtime.contextUsed * 100 / runtime.contextSize).toInt().coerceIn(0, 100)
            Text("ctx $used%", style = MaterialTheme.typography.labelSmall, fontFamily = ForgeCodeFont,
                color = if (used >= 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
        }
        // Stop sits in the working bar by the composer and Resume in the banner that replaces it; only Start lives here.
        if (controller.offers(SessionAction.Start)) {
            TextButton(onClick = { controller.perform(SessionAction.Start) }, contentPadding = PaddingValues(horizontal = 10.dp),
                enabled = controller.enabled && !model.sending && !model.inputs.busy && !model.tools.fileSaving,
                modifier = Modifier.height(34.dp)) {
                if (controller.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Start", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ConversationContent(model: ConnectionViewModel, chat: ChatViewState, composer: Boolean) {
    val session = model.session ?: return
    val pickAttachments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::addDocuments)
    // Paths in agent text open in Files (images preview; changed files show their diff).
    CompositionLocalProvider(LocalFileOpener provides model::openFileFromChat) {
    Column(Modifier.fillMaxSize()) {
        key(session.id) {
            val listState = chat.list
            val scope = rememberCoroutineScope()
            var expandedGroups by chat::expandedGroups
            val outgoing = model.pendingOutgoing
            // Settled approvals become a badge on their tool row instead of a duplicate card.
            val groups = remember(model.messages, outgoing) {
                conversationGroups(visibleMessages(model.messages), outgoing.mapNotNull { it.after })
            }
            // A sent prompt keeps its place in the thread; replies that stream in before Kandev's copy go below it.
            val outgoingAt = remember(groups, outgoing) { outgoing.groupBy { outgoingAnchor(groups, it.after) } }
            val approvals = remember(model.messages) { approvalsByToolCall(model.messages) }
            // While the agent works, its latest step opens by itself so you can follow along; it folds once the next one starts.
            val working = session.state in setOf("RUNNING", "STARTING")
            val liveId = if (!working) null else groups.lastOrNull()?.messages?.lastOrNull()
                ?.takeIf { isToolMessage(it) || it.type == "thinking" }?.id
            // Follow the conversation unless you scrolled up yourself to read; reaching the bottom resumes following.
            var following by chat::following
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }.collect { (scrolling, index) ->
                    if (index == 0) following = true else if (scrolling) following = false
                }
            }
            // Sending is your own action: always bring the new bubble into view.
            LaunchedEffect(outgoing.size) { if (outgoing.isNotEmpty()) { following = true; listState.scrollToItem(0) } }
            LaunchedEffect(groups.size, groups.lastOrNull()?.messages?.size) { if (following) listState.scrollToItem(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.asReversed().forEachIndexed { index, group ->
                        outgoingAt[groups.size - 1 - index]?.let { sent -> items(sent.asReversed(), key = { "out:${it.id}" }) { OutgoingBubble(it) } }
                        if (group.messages.size > 1 && isToolMessage(group.messages.first())) {
                            val expanded = expandedGroups.contains(group.id)
                            if (expanded) items(group.messages.asReversed(), key = { it.id }) { MessageCard(it, model, true, approvals, it.id == liveId) }
                            // A folded group still shows the step running right now, under its header.
                            else group.messages.lastOrNull()?.takeIf { it.id == liveId }?.let { current ->
                                item(key = current.id) { MessageCard(current, model, true, approvals, true) }
                            }
                            item(key = "group:${group.id}") {
                                ToolGroupHeader(group.messages, expanded) {
                                    expandedGroups = if (expanded) expandedGroups - group.id else expandedGroups + group.id
                                }
                            }
                        } else {
                            // Agent name/time only where the speaker changes, to keep the thread dense.
                            val older = groups.getOrNull(groups.size - 2 - index)?.messages?.lastOrNull()
                            val afterSent = outgoingAt.containsKey(groups.size - 2 - index)
                            items(group.messages.asReversed(), key = { it.id }) { message ->
                                MessageCard(message, model, older == null || afterSent || older.author != message.author || isToolMessage(older), approvals,
                                    message.id == liveId)
                            }
                        }
                    }
                    outgoingAt[-1]?.let { sent -> items(sent.asReversed(), key = { "out:${it.id}" }) { OutgoingBubble(it) } }
                    if (model.hasOlder) item(key = "older") {
                        TextButton(onClick = model::loadOlder, enabled = !model.loadingOlder && !model.syncingHistory,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (model.loadingOlder) "Loading…" else "Load earlier messages")
                        }
                    }
                    if (model.messages.isEmpty() && outgoing.isEmpty()) item {
                        if (model.live) EmptyState(Icons.Rounded.Forum, "No messages yet", "Send a prompt to get the agent going.")
                        else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                    }
                }
                val away by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
                if (away) SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Scroll to latest")
                }
            }
        }
        if (session.state in setOf("RUNNING", "STARTING")) WorkingBar(model, session)
        else TodoStrip(model.runtimes[session.id], false)
        // An ended session can't take messages; offer the ways back instead of a composer that would be refused.
        if (session.state in ENDED_STATES) SessionEndedBanner(model, session)
        else if (composer) Composer(model, session, onAttach = { pickAttachments.launch(arrayOf("*/*")) })
    }
    }
}

/**
 * While the agent works: what it is doing right now, for how long, its plan progress, and Stop —
 * right above the composer, where your thumb already is.
 */
@Composable
private fun WorkingBar(model: ConnectionViewModel, session: ca.mslab.forge.data.AgentSession) {
    val status = ForgeTheme.status
    val runtime = model.runtimes[session.id]
    val controller = model.sessionActions
    val doing = remember(model.messages, runtime?.activity) {
        model.messages.lastOrNull { it.author != "user" }?.let { ca.mslab.forge.data.sync.activityLine(it) }?.takeIf { it.isNotBlank() }
            ?: runtime?.activity?.takeIf { it.isNotBlank() } ?: "Starting…"
    }
    val started = model.turnStartedAt
    val elapsed by produceState("", started) {
        val since = started?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@produceState
        while (true) {
            val seconds = java.time.Duration.between(since, Instant.now()).seconds.coerceAtLeast(0)
            value = if (seconds < 3600) "%d:%02d".format(seconds / 60, seconds % 60) else "${seconds / 3600}h ${seconds % 3600 / 60}m"
            kotlinx.coroutines.delay(1_000)
        }
    }
    val todos = remember(runtime?.todosJson) {
        runtime?.todosJson?.let { runCatching { storeJson.decodeFromString(ListSerializer(TodoItem.serializer()), it) }.getOrNull() }.orEmpty()
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = status.infoContainer.copy(alpha = 0.55f)) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = status.info, trackColor = Color.Transparent)
            Row(Modifier.fillMaxWidth().clickable(enabled = todos.isNotEmpty()) { expanded = !expanded }
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseDot(status.info, size = 8.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (session.cancellationPending) "Stopping…" else if (session.activity == "background") "Background work" else "Working", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, color = status.info)
                        if (elapsed.isNotEmpty()) Text(elapsed, style = MaterialTheme.typography.labelMedium, fontFamily = ForgeCodeFont,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (todos.isNotEmpty()) Text("· ${todos.count { it.status == "completed" }}/${todos.size} steps",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(doing, style = MaterialTheme.typography.labelMedium, fontFamily = ForgeCodeFont, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                }
                // Interrupts this turn only; the agent stays up, so you can steer it with your next message.
                if (controller.offers(SessionAction.Interrupt)) TextButton(onClick = { controller.perform(SessionAction.Interrupt) },
                    enabled = controller.enabled && !model.sending && !model.inputs.busy && !model.tools.fileSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    if (controller.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Stop, "Stop this turn", Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Stop", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (expanded) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                todos.forEach { TodoLine(it) }
            }
        }
    }
}

@Composable
private fun TodoLine(item: TodoItem) {
    val status = ForgeTheme.status
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(when (item.status) {
            "completed" -> Icons.Rounded.CheckCircle
            "in_progress" -> Icons.Rounded.PlayCircle
            "failed" -> Icons.Rounded.Error
            else -> Icons.Rounded.RadioButtonUnchecked
        }, null, Modifier.size(15.dp), tint = when (item.status) {
            "completed" -> status.success; "in_progress" -> status.info
            "failed" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline
        })
        Text(item.description, style = MaterialTheme.typography.bodySmall,
            color = if (item.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

/** The agent's plan for this turn: "3/7 · Writing tests", expandable to the full list. */
@Composable
private fun TodoStrip(runtime: RuntimeRow?, running: Boolean) {
    val todos = remember(runtime?.todosJson) {
        runtime?.todosJson?.let { runCatching { storeJson.decodeFromString(ListSerializer(TodoItem.serializer()), it) }.getOrNull() }.orEmpty()
    }
    if (todos.isEmpty() || (!running && todos.all { it.status == "completed" })) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val status = ForgeTheme.status
    val done = todos.count { it.status == "completed" }
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status != "completed" }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(progress = { done / todos.size.toFloat() }, modifier = Modifier.size(16.dp), strokeWidth = 2.5.dp,
                    color = status.info, trackColor = MaterialTheme.colorScheme.outlineVariant)
                Text("$done/${todos.size}", style = MaterialTheme.typography.labelMedium, fontFamily = ForgeCodeFont)
                Text(current?.description.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline)
            }
            if (expanded) todos.forEach { TodoLine(it) }
        }
    }
}

@Composable
private fun Composer(model: ConnectionViewModel, session: ca.mslab.forge.data.AgentSession, onAttach: () -> Unit) {
    val mode = model.inputMode
    val queueing = mode == InputMode.Queue
    val canQueue = queueing && session.queueIncarnationId.isNotBlank() && model.attachments.files.isEmpty()
    val canSend = model.live && model.inputs.pending == null && !model.sending && !model.attachments.importing &&
        !model.sessionActions.busy && (model.draft.isNotBlank() || model.attachments.files.isNotEmpty()) &&
        mode in setOf(InputMode.Direct, InputMode.Steer)
    val queueEnabled = model.live && canQueue && !model.sending && model.draft.isNotBlank() && !model.queue.full
    val enabled = if (queueing) queueEnabled else canSend
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            model.sendError?.let { ErrorNotice(it) }
            model.queue.error?.let { ErrorNotice(it) }
            if (model.queue.count > 0) QueuePanel(model)
            AttachmentComposer(model)
            if (queueing && model.attachments.files.isNotEmpty()) InfoNotice("Files can't be queued. Send them once this turn ends.",
                Modifier.padding(horizontal = 4.dp), Icons.Rounded.AttachFile)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onAttach, modifier = Modifier.size(44.dp),
                    enabled = !model.sending && !model.attachments.importing && model.attachments.files.size < MAX_ATTACHMENTS) {
                    Icon(Icons.Rounded.AttachFile, "Attach files")
                }
                // Hardware keyboards (tablets, Chromebooks): Ctrl/⌘+Enter sends; plain Enter stays a newline.
                TextField(value = model.draft, onValueChange = model::editDraft,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                            if (enabled) model.submitDraft(); true
                        } else false
                    }, enabled = !model.sending, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text(when (mode) {
                        InputMode.Queue -> "Queue a follow-up…"
                        InputMode.Steer -> "Steer the agent…"
                        else -> "Message the agent…"
                    }, style = MaterialTheme.typography.bodyMedium) },
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
                FilledIconButton(onClick = model::submitDraft, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    if (model.sending || model.attachments.uploading != null)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else if (queueing) Icon(Icons.Rounded.AddToQueue, "Queue follow-up", Modifier.size(20.dp))
                    else Icon(Icons.AutoMirrored.Rounded.Send, if (mode == InputMode.Steer) "Send into this turn" else "Send", Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolGroupHeader(messages: List<ChatMessage>, expanded: Boolean, onToggle: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onToggle), shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${messages.size} tool calls", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                messages.map { toolIcon(it) }.distinct().take(5).forEach {
                    Icon(it, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand")
        }
    }
}

private fun toolIcon(message: ChatMessage): ImageVector = when (message.type) {
    "tool_execute", "script_execution" -> Icons.Rounded.Terminal
    "tool_read" -> Icons.Rounded.Visibility
    "tool_edit", "tool_write" -> Icons.Rounded.EditNote
    "tool_search", "tool_grep", "tool_glob" -> Icons.Rounded.Search
    "tool_fetch", "tool_web" -> Icons.Rounded.Public
    "thinking" -> Icons.Rounded.Psychology
    "log", "status" -> Icons.Rounded.Info
    "permission_request" -> Icons.Rounded.Shield
    "clarification_request" -> Icons.Rounded.QuestionAnswer
    else -> Icons.Rounded.Build
}

@Composable
private fun MessageCard(message: ChatMessage, model: ConnectionViewModel, header: Boolean,
    approvals: Map<String, ChatMessage> = emptyMap(), live: Boolean = false) {
    val tool = message.type.startsWith("tool_") || message.type in setOf("log", "script_execution")
    // An open approval usually settles within a second via provider auto-approval; only show it if it lingers.
    if (message.isOpenPermission()) {
        var lingering by remember(message.id) { mutableStateOf(false) }
        LaunchedEffect(message.id) { kotlinx.coroutines.delay(2_500); lingering = true }
        if (!lingering) return
    }
    when {
        message.author == "user" -> UserBubble(message, model)
        tool || message.type in setOf("thinking", "status") ->
            ToolRow(message, model, tool, message.metadata?.toolCallId?.let { approvals[it] }, live)
        else -> AgentMessage(message, model, header)
    }
}

/** Your prompts: compact, right-aligned. */
@Composable
private fun UserBubble(message: ChatMessage, model: ConnectionViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, start = 40.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.content.isNotBlank()) MarkdownText(message.content, Modifier.wrapContentWidth())
                MessageAttachments(message, model)
            }
        }
        Text(clockTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 4.dp))
    }
}

/** A message you just sent: shown at once, marked until Kandev's copy replaces it in the stream. */
@Composable
private fun OutgoingBubble(message: OutgoingMessage) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, start = 40.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.content.isNotBlank()) MarkdownText(message.content, Modifier.wrapContentWidth())
                if (message.files > 0) MetaItem(Icons.Rounded.AttachFile, if (message.files == 1) "1 file" else "${message.files} files")
            }
        }
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.acknowledged) Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
            else CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.outline)
            Text(if (message.acknowledged) "Sent" else "Sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Agent text runs full width without a bubble, like a document; requests get an accent rail. */
@Composable
private fun AgentMessage(message: ChatMessage, model: ConnectionViewModel, header: Boolean) {
    // Long replies open in full; "Show less" folds them when you want to skim past.
    var expanded by rememberSaveable(message.id) { mutableStateOf(true) }
    val status = ForgeTheme.status
    val request = message.type in setOf("permission_request", "clarification_request")
    val content = (if (message.type == "clarification_request") message.metadata?.question?.prompt ?: message.content else message.content)
        .ifBlank { message.type.label() }
    val long = content.length > 2000
    val (icon, title) = when (message.type) {
        "clarification_request" -> Icons.Rounded.QuestionAnswer to (message.metadata?.question?.title?.ifBlank { "Question" } ?: "Question")
        "permission_request" -> Icons.Rounded.Shield to "Approval"
        "message", "content" -> Icons.Rounded.AutoAwesome to (model.task?.agentName?.takeIf { it.isNotBlank() } ?: "Agent")
        else -> Icons.Rounded.Info to (message.metadata?.title ?: message.type.label())
    }
    Column(Modifier.fillMaxWidth().padding(top = if (header || request) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (header || request) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.type in setOf("message", "content")) AgentMark(model.session?.agentProfileId?.ifBlank { null } ?: model.task?.agentProfileId, size = 14.dp)
            else Icon(icon, null, Modifier.size(14.dp), tint = if (request) status.warning else MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                color = if (request) status.warning else MaterialTheme.colorScheme.onSurface)
            Text(clockTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (request) Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(status.warning.copy(alpha = 0.6f)))
            Column(Modifier.weight(1f).padding(start = if (request) 10.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownText(content, Modifier.fillMaxWidth(), if (long && !expanded) 8 else Int.MAX_VALUE)
                if (long) ShowMore(expanded) { expanded = !expanded }
                MessageAttachments(message, model)
                if (request) RequestDetails(message)
                if (message.requestsInput && !request) InfoNotice("This input type is not supported yet. Respond in the Kandev web client.",
                    icon = Icons.Rounded.OpenInBrowser)
            }
        }
    }
}

@Composable
private fun RequestDetails(message: ChatMessage) {
    val status = ForgeTheme.status
    if (message.type == "permission_request") (message.metadata?.actionDetails?.command ?: message.metadata?.actionDetails?.path)?.let {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraSmall) {
            SelectionContainer { Text(it, fontFamily = ForgeCodeFont, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(8.dp), maxLines = 6, overflow = TextOverflow.Ellipsis) }
        }
    }
    message.metadata?.response?.let { answer ->
        val labels = answer.selected.map { id -> message.metadata.question?.options?.firstOrNull { it.id == id }?.label ?: id }
        MetaItem(Icons.AutoMirrored.Rounded.Reply, (labels + answer.text.takeIf { it.isNotBlank() }.let(::listOfNotNull)).joinToString(" · "))
    }
    val state = message.metadata?.status ?: "pending"
    val (color, icon) = when (state) {
        "approved", "answered", "allowed", "complete", "completed" -> status.success to Icons.Rounded.CheckCircle
        "rejected", "denied", "cancelled", "expired" -> MaterialTheme.colorScheme.error to Icons.Rounded.Cancel
        else -> status.warning to Icons.Rounded.Schedule
    }
    MetaItem(icon, state.label(), color = color)
}

@Composable
private fun ToolRow(message: ChatMessage, model: ConnectionViewModel, tool: Boolean, approval: ChatMessage? = null, live: Boolean = false) {
    // Your own tap wins; otherwise the row is open exactly while it is the step the agent is on.
    var choice by rememberSaveable(message.id) { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: live
    val status = ForgeTheme.status
    val shell = message.metadata?.normalized?.shell
    val title = message.metadata?.title?.takeIf { it.isNotBlank() } ?: shell?.command?.takeIf { it.isNotBlank() }
        ?: message.content.lineSequence().firstOrNull { it.isNotBlank() } ?: message.type.label()
    val exit = shell?.output?.exitCode
    val stateColor = when {
        exit != null && exit != 0 -> MaterialTheme.colorScheme.error
        message.metadata?.status in setOf("in_progress", "running", "pending") -> status.info
        message.metadata?.status in setOf("failed", "error") -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { choice = !expanded }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(toolIcon(message), null, Modifier.size(16.dp), tint = stateColor)
                Text(title, style = MaterialTheme.typography.bodySmall, fontFamily = if (tool) ForgeCodeFont else null,
                    fontStyle = if (message.type == "thinking") FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (live || message.metadata?.status in setOf("in_progress", "running"))
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = status.info)
                else if (exit != null) Tag("exit $exit", mono = true,
                    container = if (exit == 0) status.successContainer else MaterialTheme.colorScheme.errorContainer,
                    content = if (exit == 0) status.success else MaterialTheme.colorScheme.error)
                val filePath = message.toolFilePath?.takeIf { isOpenableFilePath(it) }
                if (filePath != null) IconButton(onClick = { model.openFileFromChat(filePath) }, modifier = Modifier.size(28.dp)) {
                    Icon(if (isImagePath(filePath)) Icons.Rounded.Image else Icons.AutoMirrored.Rounded.OpenInNew,
                        if (isImagePath(filePath)) "Preview image" else "Open file", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                // The approval this call needed, folded in: a quiet shield instead of a second card.
                if (approval != null) Icon(Icons.Rounded.VerifiedUser,
                    if (approval.isAutoApproved()) "Auto-approved" else "Approved", Modifier.size(14.dp),
                    tint = if (approval.isAutoApproved()) MaterialTheme.colorScheme.outline else status.success)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message.imageReadPath?.let { ChatImageThumbnail(message, it, model) }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (approval != null) MetaItem(Icons.Rounded.VerifiedUser,
                        if (approval.isAutoApproved()) "Auto-approved by your provider settings" else "Approved")
                    if (message.content.isNotBlank()) SelectionContainer {
                        // Live output can be long; keep the newest lines in view rather than the whole log.
                        if (tool) Text(if (live) message.content.lines().takeLast(12).joinToString("\n") else message.content,
                            style = MaterialTheme.typography.bodySmall, fontFamily = ForgeCodeFont)
                        else MarkdownText(message.content, Modifier.fillMaxWidth())
                    }
                    MessageAttachments(message, model)
                    if (tool && shell?.output?.hasOutput == true) {
                        val loading = model.loadingOutputs.contains(message.id)
                        FilledTonalButton(onClick = { model.loadToolOutput(message) }, enabled = !loading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Icon(if (model.toolOutputs.containsKey(message.id)) Icons.Rounded.Refresh else Icons.Rounded.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (loading) "Loading output…" else if (model.toolOutputs.containsKey(message.id)) "Refresh output" else "Load output",
                                style = MaterialTheme.typography.labelMedium)
                        }
                        model.outputErrors[message.id]?.let { ErrorNotice(it) }
                        model.toolOutputs[message.id]?.output?.let { output ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.extraSmall) {
                                CodeLines(output.stdout + if (output.stderr.isNotEmpty()) "\n" + output.stderr else "",
                                    Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the agent looked at, small and always in view; tap opens the full, zoomable preview. */
@Composable
private fun ChatImageThumbnail(message: ChatMessage, path: String, model: ConnectionViewModel) {
    // The file exists by the time the agent reads it, so loading needn't wait for the step to finish.
    var failed by remember(message.id) { mutableStateOf(false) }
    val bitmap by produceState(model.cachedChatImage(message.id), message.id, model.live) {
        if (value == null && model.live) { value = model.chatImage(message, path); failed = value == null }
    }
    val name = path.substringAfterLast('/')
    Box(Modifier.padding(start = 36.dp, end = 10.dp, bottom = 8.dp).clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { model.openFileFromChat(path) },
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, "Preview of $name", contentScale = ContentScale.Fit,
            modifier = Modifier.height(160.dp).aspectRatio((image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(0.4f, 2.4f)))
        else Box(Modifier.size(width = 120.dp, height = 90.dp), contentAlignment = Alignment.Center) {
            if (failed) Icon(Icons.Rounded.BrokenImage, "Preview unavailable", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.outline)
            else if (model.live) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
            else Icon(Icons.Rounded.Image, name, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ShowMore(expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(if (expanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelMedium)
    }
}
