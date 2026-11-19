package ca.mslab.forge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.StepRow
import ca.mslab.forge.data.sync.epochMillis
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme

/** Home layouts: a list grouped by step or repository, or the Kandev board. */
enum class HomeView(val label: String, val icon: ImageVector) {
    Steps("By step", Icons.Rounded.ViewAgenda),
    Repository("By repository", Icons.Rounded.FolderCopy),
    Board("Board", Icons.Rounded.ViewKanban),
}

/**
 * The Kanban board, like Kandev's web board: one column per workflow step, swiped sideways and
 * snapping to columns. Each column is ~85% wide so the next one peeks in and you know it's there.
 */
@Composable
internal fun TaskBoard(model: ConnectionViewModel, query: String, onMove: (TaskSummary) -> Unit) {
    val tasks = remember(model.tasks, model.repositories, query) { filterTasks(model.tasks, model.repositories, query) }
    // Workflows that have steps, in Kandev's order; start on the one you worked in last.
    val workflows = remember(model.workflows, model.steps) {
        model.workflows.sortedBy { it.sortOrder }.filter { flow -> model.steps.any { it.workflowId == flow.id } }
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val workflowId = chosen?.takeIf { id -> workflows.any { it.id == id } }
        ?: model.tasks.filter { !it.isDone() }.maxByOrNull { epochMillis(it.lastActivityAt) }?.workflowId
        ?: workflows.firstOrNull()?.id
    val columns = remember(tasks, model.steps, workflowId) { boardColumns(tasks, model.steps, workflowId) }
    val open = remember(model.steps) { openSteps(model.steps) }
    Column(Modifier.fillMaxSize()) {
        if (workflows.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            workflows.forEach { flow ->
                FilterChip(flow.id == workflowId, { chosen = flow.id }, label = { Text(flow.name) })
            }
        }
        if (columns.isEmpty()) {
            EmptyState(Icons.Rounded.ViewKanban, "No board yet", "This workspace has no workflow steps.")
            return
        }
        val listState = rememberLazyListState()
        // Open where the work is: the first column holding an unfinished task, not an empty Backlog.
        LaunchedEffect(workflowId) {
            val start = columns.indexOfFirst { column -> column.tasks.any { !it.isDone() } }
                .takeIf { it >= 0 } ?: columns.indexOfFirst { it.tasks.isNotEmpty() }.coerceAtLeast(0)
            listState.scrollToItem(start)
        }
        val width = (LocalConfiguration.current.screenWidthDp * 0.85f).dp.coerceAtMost(360.dp)
        LazyRow(Modifier.fillMaxSize(), state = listState, flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(columns, key = { it.step.id }) { column ->
                BoardColumnView(model, column, open, Modifier.width(width).fillMaxHeight(), onMove)
            }
        }
    }
}

@Composable
private fun BoardColumnView(model: ConnectionViewModel, column: BoardColumn, open: Set<String>, modifier: Modifier, onMove: (TaskSummary) -> Unit) {
    Surface(modifier.padding(vertical = 6.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(stepColor(column.step.color) ?: MaterialTheme.colorScheme.outline))
                Text(column.step.name.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text("${column.tasks.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            if (column.tasks.isEmpty()) Text("No tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(column.tasks, key = { it.id }) { task ->
                    val repository = task.repositories.firstOrNull()
                    BoardCard(task, repository?.let { model.repositories[it.repositoryId]?.name },
                        task.primarySessionId?.let { model.runtimes[it] }?.activity, open,
                        onLongPress = { onMove(task) }) { model.openTask(task) }
                }
            }
        }
    }
}

/** A compact card: title, status, then repository · agent and the diff size. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCard(task: TaskSummary, repository: String?, activity: String?, openSteps: Set<String>,
    onLongPress: () -> Unit, onClick: () -> Unit) {
    val status = task.agentStatus(openSteps)
    val look = statusLook(status)
    val git = task.summary?.git
    val urgent = task.needsYou()
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
        border = if (urgent) BorderStroke(1.dp, ForgeTheme.status.warning.copy(alpha = 0.7f)) else null,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress, onLongClickLabel = "Move or archive task")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.working) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = look.color)
                else Icon(look.icon, null, Modifier.size(14.dp), tint = look.color)
                Text(status.label, style = MaterialTheme.typography.labelMedium, color = look.color, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(relativeTime(task.lastActivityAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            // Running: what it is doing right now (the tool or command from the conversation, not a guess).
            if (status.working) activity?.removePrefix("Running ")?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = ForgeCodeFont, color = look.color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = buildAnnotatedString {
                repository?.let { append(it) }
                task.agentName?.takeIf { it.isNotBlank() }?.let {
                    if (length > 0) append(" · ")
                    appendInlineContent("agent", "◆"); append(" "); append(it)
                }
                if (git != null && (git.additions > 0 || git.deletions > 0)) {
                    if (length > 0) append("  ")
                    withStyle(SpanStyle(color = ForgeTheme.status.success, fontFamily = ForgeCodeFont)) { append("+${compactCount(git.additions)}") }
                    append(" ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontFamily = ForgeCodeFont)) { append("−${compactCount(git.deletions)}") }
                }
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = agentInline(task.agentProfileId))
        }
    }
}
