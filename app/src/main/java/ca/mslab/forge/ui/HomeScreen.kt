package ca.mslab.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.RuntimeRow
import ca.mslab.forge.data.sync.LinkState
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TaskListScreen(model: ConnectionViewModel, listState: LazyListState, onSettings: () -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var switcher by remember { mutableStateOf(false) }
    // Held by the view model so sections you opened stay open after visiting a task.
    var toggled by model::homeToggled
    var composing by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Pair<TaskSummary, ca.mslab.forge.data.db.StepRow?>?>(null) }
    val open = remember(model.steps) { openSteps(model.steps) }
    val sections = remember(model.tasks, model.steps, model.workflows, model.repositories, model.groupByRepository, query) {
        homeSections(model.tasks, model.steps, model.workflows, model.repositories, model.groupByRepository, query)
    }
    Column(Modifier.fillMaxSize()) {
        HomeHeader(model, onWorkspace = { switcher = true },
            onSearch = { searching = !searching; if (!searching) query = "" }, onSettings = onSettings)
        AnimatedVisibility(searching) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Title, ID, agent or repository") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                shape = CircleShape, textStyle = MaterialTheme.typography.bodyMedium)
        }
        model.error?.let { ErrorNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        UsageStrip(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        PullToRefreshBox(isRefreshing = model.busy && model.tasks.isNotEmpty(), onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize()) {
            if (model.homeView == HomeView.Board && model.tasks.isNotEmpty()) TaskBoard(model, query) { moving = it to null }
            else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (model.tasks.isEmpty()) item {
                    if (model.busy) Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else EmptyState(Icons.Rounded.Inbox, "No tasks yet",
                        "Tasks created in ${model.workspace?.name ?: "this workspace"} appear here.")
                } else if (sections.isEmpty()) item {
                    EmptyState(Icons.Rounded.SearchOff, "Nothing matches", "Try another search.")
                }
                for (section in sections) {
                    val collapsed = section.collapsedByDefault xor toggled.contains(section.key)
                    stickyHeader(key = "h:${section.key}") {
                        SectionHeader(section, collapsed) {
                            toggled = if (toggled.contains(section.key)) toggled - section.key else toggled + section.key
                        }
                    }
                    if (!collapsed) items(section.tasks, key = { "${section.key}:${it.task.id}" }) { placed ->
                        val repository = placed.task.repositories.firstOrNull()
                        TaskRowItem(placed.task, placed.depth,
                            repository?.let { model.repositories[it.repositoryId]?.name }, repository?.baseBranch,
                            placed.task.primarySessionId?.let { model.runtimes[it] },
                            showRepository = !model.groupByRepository, openSteps = open,
                            onLongPress = { moving = placed.task to null },
                            onMoveToDone = {
                                moving = placed.task to model.steps.firstOrNull { it.workflowId == placed.task.workflowId && it.completesTask }
                            }) { model.openTask(placed.task) }
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
        ExtendedFloatingActionButton(onClick = { model.clearCreateError(); model.loadLaunchOptions(); composing = true },
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New task") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        }
    }
    if (switcher) WorkspaceSwitcher(model, onDismiss = { switcher = false }, onSettings = { switcher = false; onSettings() })
    if (composing) NewTaskSheet(model) { composing = false }
    moving?.let { (task, step) -> MoveTaskSheet(model, task, step) { moving = null } }
}

@Composable
private fun HomeHeader(model: ConnectionViewModel, onWorkspace: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClick = onWorkspace).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.workspace?.name ?: "Choose a workspace", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.UnfoldMore, "Switch workspace", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ConnectionLine(model)
        }
        Box {
            var views by remember { mutableStateOf(false) }
            IconButton(onClick = { views = true }) { Icon(model.homeView.icon, "View: ${model.homeView.label}") }
            DropdownMenu(views, { views = false }) {
                HomeView.entries.forEach { view ->
                    DropdownMenuItem(text = { Text(view.label) }, leadingIcon = { Icon(view.icon, null) },
                        trailingIcon = { if (view == model.homeView) Icon(Icons.Rounded.Check, "Current", tint = MaterialTheme.colorScheme.primary) },
                        onClick = { views = false; model.chooseHomeView(view) })
                }
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search tasks") }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

/** Always-visible connection state; the live link drives it, not a guess. */
@Composable
internal fun ConnectionLine(model: ConnectionViewModel) {
    val shown by model.hub.link.settled.collectAsStateWithLifecycle()
    val status = ForgeTheme.status
    val open = remember(model.steps) { openSteps(model.steps) }
    val counts = model.tasks.groupingBy { it.agentStatus(open) }.eachCount()
    val running = (counts[AgentStatus.Running] ?: 0) + (counts[AgentStatus.Background] ?: 0)
    val needs = (counts[AgentStatus.Approval] ?: 0) + (counts[AgentStatus.Question] ?: 0) + (counts[AgentStatus.Error] ?: 0)
    val turn = counts[AgentStatus.YourTurn] ?: 0
    val (color, text) = when (shown) {
        LinkState.Live -> status.success to listOfNotNull(model.routeLabel,
            if (running > 0) "$running running" else null, if (needs > 0) "$needs need you" else null,
            if (turn > 0) "$turn your turn" else null).joinToString(" · ")
        LinkState.Connecting -> status.info to "Reconnecting…"
        LinkState.Offline -> status.warning to "Offline · saved ${model.tasks.size} tasks"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PulseDot(color, size = 7.dp, pulsing = shown != LinkState.Live)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(section: HomeSection, collapsed: Boolean, onToggle: () -> Unit) {
    val status = ForgeTheme.status
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section.attention) Icon(Icons.Rounded.BackHand, null, Modifier.size(14.dp), tint = status.warning)
            else Box(Modifier.size(8.dp).clip(CircleShape).background(stepColor(section.color) ?: MaterialTheme.colorScheme.outline))
            Text(section.title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (section.attention) status.warning else MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp)
            Text("${section.tasks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            section.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, if (collapsed) "Expand" else "Collapse",
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Two compact lines (title + time; status · repo · branch · agent · ±lines), a third for live detail. */
@Composable
internal fun TaskRowItem(task: TaskSummary, depth: Int, repository: String?, branch: String?, runtime: RuntimeRow?,
    showRepository: Boolean = true, openSteps: Set<String> = emptySet(), onLongPress: (() -> Unit)? = null,
    onMoveToDone: (() -> Unit)? = null, onClick: () -> Unit) {
    val status = task.agentStatus(openSteps)
    val look = statusLook(status)
    val git = task.summary?.git
    val done = status == AgentStatus.Completed || status == AgentStatus.Cancelled
    val meta = buildAnnotatedString {
        withStyle(SpanStyle(color = look.color, fontWeight = FontWeight.SemiBold)) { append(status.label) }
        if (showRepository && repository != null) { append(" · "); append(repository) }
        if (showRepository && !branch.isNullOrBlank()) { append(" · ⎇ "); append(branch) }
        task.agentName?.takeIf { it.isNotBlank() }?.let { append(" · "); appendInlineContent("agent", "◆"); append(" "); append(it) }
        if (task.sessionCount > 1) append(" · ${task.sessionCount} sessions")
        if (git != null && (git.additions > 0 || git.deletions > 0)) {
            append("  ")
            withStyle(SpanStyle(color = ForgeTheme.status.success, fontFamily = ForgeCodeFont)) { append("+${compactCount(git.additions)}") }
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontFamily = ForgeCodeFont)) { append("−${compactCount(git.deletions)}") }
        }
    }
    val detail = when (status) {
        // The status already says "Running"; show only what it is running.
        AgentStatus.Running, AgentStatus.Background -> runtime?.activity?.removePrefix("Running ")?.takeIf { it.isNotBlank() }
        AgentStatus.Approval -> runtime?.activity?.takeIf { it.isNotBlank() && it != "Waiting for approval" }
        AgentStatus.Error, AgentStatus.Failed -> task.summary?.activeError?.preview?.takeIf { it.isNotBlank() }
        else -> null
    }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress, onLongClickLabel = "Move or archive task")
        .padding(start = 16.dp + (depth * 18).dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) { StatusGlyph(status, look) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (depth > 0) Text("↳", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(task.title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(relativeTime(task.lastActivityAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = agentInline(task.agentProfileId))
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = look.color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = if (status.working) ForgeCodeFont else null)
            if (status == AgentStatus.Finished && onMoveToDone != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent finished; the card wasn't moved.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onMoveToDone, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) {
                    Icon(Icons.Rounded.TaskAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text("Move to Done", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The provider logo, sized to the line it sits in, for `appendInlineContent("agent", …)`. */
internal fun agentInline(profileId: String?) = mapOf("agent" to androidx.compose.foundation.text.InlineTextContent(
    androidx.compose.ui.text.Placeholder(13.sp, 13.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)) {
    AgentMark(profileId, Modifier.fillMaxSize(), size = 13.dp, fallbackTint = MaterialTheme.colorScheme.onSurfaceVariant)
})

internal data class StatusLook(val icon: ImageVector, val color: Color)

@Composable
internal fun statusLook(status: AgentStatus): StatusLook {
    val tone = ForgeTheme.status
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        AgentStatus.Approval -> StatusLook(Icons.Rounded.BackHand, tone.warning)
        AgentStatus.Question -> StatusLook(Icons.Rounded.QuestionAnswer, tone.warning)
        AgentStatus.Running -> StatusLook(Icons.Rounded.PlayCircle, tone.info)
        AgentStatus.Background -> StatusLook(Icons.Rounded.HourglassBottom, tone.info)
        AgentStatus.Failed, AgentStatus.Error -> StatusLook(Icons.Rounded.ErrorOutline, scheme.error)
        AgentStatus.YourTurn -> StatusLook(Icons.Rounded.ChatBubble, scheme.primary)
        AgentStatus.Completed -> StatusLook(Icons.Rounded.CheckCircle, tone.success)
        AgentStatus.Finished -> StatusLook(Icons.Rounded.TaskAlt, scheme.primary)
        AgentStatus.Cancelled -> StatusLook(Icons.Rounded.Cancel, tone.neutral)
        AgentStatus.NotStarted -> StatusLook(Icons.Rounded.RadioButtonUnchecked, tone.neutral)
    }
}

@Composable
private fun StatusGlyph(status: AgentStatus, look: StatusLook) {
    if (status.working) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = look.color)
    else Icon(look.icon, status.label, Modifier.size(16.dp).alpha(if (status == AgentStatus.Completed) 0.75f else 1f), tint = look.color)
}

/** Kandev step colors are Tailwind classes such as `bg-blue-500`. */
internal fun stepColor(value: String?): Color? {
    val name = value?.removePrefix("bg-")?.substringBefore('-') ?: return null
    return when (name) {
        "red" -> Color(0xFFEF4444); "orange" -> Color(0xFFF97316); "amber" -> Color(0xFFF59E0B)
        "yellow" -> Color(0xFFEAB308); "lime" -> Color(0xFF84CC16); "green" -> Color(0xFF22C55E)
        "emerald" -> Color(0xFF10B981); "teal" -> Color(0xFF14B8A6); "cyan" -> Color(0xFF06B6D4)
        "sky" -> Color(0xFF0EA5E9); "blue" -> Color(0xFF3B82F6); "indigo" -> Color(0xFF6366F1)
        "violet" -> Color(0xFF8B5CF6); "purple" -> Color(0xFFA855F7); "fuchsia" -> Color(0xFFD946EF)
        "pink" -> Color(0xFFEC4899); "rose" -> Color(0xFFF43F5E)
        "slate", "gray", "zinc", "neutral", "stone" -> Color(0xFFA3A3A3)
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSwitcher(model: ConnectionViewModel, onDismiss: () -> Unit, onSettings: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workspaces", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            model.snapshot?.workspaces.orEmpty().forEach { workspace ->
                WorkspaceRow(workspace, workspace.id == model.workspace?.id) {
                    if (workspace.id != model.workspace?.id) model.openWorkspace(workspace)
                    onDismiss()
                }
            }
            TextButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Settings")
            }
        }
    }
}

