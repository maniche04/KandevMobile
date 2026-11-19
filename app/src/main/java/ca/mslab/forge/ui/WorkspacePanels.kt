package ca.mslab.forge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.*
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspacePanel(model: WorkspaceController) {
    Column(Modifier.fillMaxSize()) {
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (model.tab != WorkspaceTab.Terminal) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val (icon, text) = when {
                !model.connected -> Icons.Rounded.CloudOff to "Saved content · offline"
                model.savedAt != null -> Icons.Rounded.CloudSync to "Saved content · refreshing…"
                else -> Icons.Rounded.CloudDone to "Up to date"
            }
            MetaItem(icon, text, Modifier.weight(1f))
            IconButton(onClick = model::refresh, enabled = model.connected && !model.busy) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }
        model.error?.let { ErrorNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        // A file tapped in the chat that couldn't open: one tap back to where you were reading.
        if (model.returnsToChat && model.file == null && !model.busy) {
            FilledTonalButton(onClick = model::back, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back to chat")
            }
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (model.tab) {
                WorkspaceTab.Plan -> PlanPanel(model)
                WorkspaceTab.Changes -> ChangesPanel(model)
                WorkspaceTab.Files -> FilesPanel(model)
                WorkspaceTab.Terminal -> TerminalPanel(model)
                else -> Unit
            }
        }
    }
}

@Composable
private fun PlanPanel(model: WorkspaceController) {
    val snapshot = model.plan
    val draft = model.planDraft
    if (draft != null) {
        PlanEditor(model, draft)
        return
    }
    if (snapshot?.plan == null) {
        if (snapshot != null) EmptyState(Icons.Rounded.Checklist, "No plan yet", "The agent hasn't written a plan for this task.") {
            if (model.connected) FilledTonalButton(onClick = model::editPlan) {
                Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Write a plan")
            }
        }
        else if (model.connected) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        else EmptyState(Icons.Rounded.CloudOff, "Plan not saved", "Connect to load this task's plan.")
    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.Checklist)
                Column(Modifier.weight(1f)) {
                    Text(snapshot.plan.title.ifBlank { "Plan" }, style = MaterialTheme.typography.titleLarge)
                    relativeTime(snapshot.plan.updatedAt).takeIf { it.isNotBlank() }?.let {
                        MetaItem(Icons.Rounded.Schedule, "Updated $it${if (it == "now") "" else " ago"}")
                    }
                }
                FilledTonalButton(onClick = model::editPlan, enabled = model.connected && !model.busy,
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                MarkdownText(snapshot.plan.content, Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

/** Markdown source editing; the agent reads the plan as text, so no rich editor is needed. */
@Composable
private fun PlanEditor(model: WorkspaceController, draft: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Edit plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = model::cancelPlanEdit, enabled = !model.planSaving) { Text("Cancel") }
            Button(onClick = model::savePlan, enabled = model.connected && !model.planSaving && draft.isNotBlank()) {
                if (model.planSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Save")
            }
        }
        OutlinedTextField(draft, model::updatePlanDraft, Modifier.fillMaxWidth().weight(1f), enabled = !model.planSaving,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ForgeCodeFont),
            placeholder = { Text("Markdown: goals, steps, acceptance criteria…") })
        Spacer(Modifier.height(4.dp))
    }
}

/** Commit, push and open a pull request for the session's branch, one explicit request each. */
@Composable
private fun GitActions(model: WorkspaceController, status: GitStatus, dirty: Boolean) {
    var commit by remember { mutableStateOf(false) }
    var pr by remember { mutableStateOf(false) }
    val busy = model.gitBusy
    val enabled = model.connected && busy == null
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { commit = true }, enabled = enabled && dirty, contentPadding = PaddingValues(horizontal = 12.dp)) {
            if (busy == "commit") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text("Commit")
        }
        OutlinedButton(onClick = { model.push(status.repository) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
            if (busy == "push") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.CloudUpload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text(if (status.ahead > 0) "Push ${status.ahead}" else "Push")
        }
        OutlinedButton(onClick = { pr = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
            if (busy == "pr") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Rounded.AltRoute, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text("Pull request")
        }
    }
    if (commit) {
        var message by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { commit = false }, title = { Text("Commit all changes") },
            text = { OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") }, maxLines = 6) },
            confirmButton = { TextButton(onClick = { model.commit(message, status.repository); commit = false }, enabled = message.isNotBlank()) { Text("Commit") } },
            dismissButton = { TextButton(onClick = { commit = false }) { Text("Cancel") } })
    }
    if (pr) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var base by remember { mutableStateOf(model.baseBranch) }
        var draft by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { pr = false }, title = { Text("Open a pull request") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, maxLines = 6)
                    OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base branch") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft, { draft = it }); Text("Draft", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Push the branch first if it isn't on the remote yet.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { model.createPullRequest(title, body, base.trim(), draft, status.repository); pr = false },
                enabled = title.isNotBlank() && base.isNotBlank()) { Text("Open") } },
            dismissButton = { TextButton(onClick = { pr = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ChangesPanel(model: WorkspaceController) {
    val selected = model.selectedDiff
    if (selected != null) {
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.selectDiff(null) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "All changes") }
                Column(Modifier.weight(1f)) {
                    Text(selected.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontFamily = ForgeCodeFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    selected.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = ForgeCodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DiffStat(selected.additions, selected.deletions)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val diff = selected.diff ?: selected.unstagedChange?.diff ?: selected.stagedChange?.diff
            if (diff.isNullOrBlank()) EmptyState(Icons.Rounded.Difference, "Diff not available",
                "Refresh Changes, or open the file from the Files tab.")
            else CodeLines(diff, diff = true, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val working = model.changes.statuses.values.flatMap { status -> status.files.values.map { it.copy(repository = status.repository) } }
    val cumulative = model.changes.cumulative.values.toList()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        model.gitNotice?.let { notice -> item {
            Surface(color = ForgeTheme.status.successContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkdownText(notice, Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = model::clearGitNotice) { Icon(Icons.Rounded.Close, "Dismiss") }
                }
            }
        } }
        model.changes.statuses.values.forEach { status -> item {
            RepositoryStatus(status)
            if (status.statusState == "ready") Box(Modifier.padding(top = 6.dp)) { GitActions(model, status, status.files.isNotEmpty()) }
        } }
        if (working.isEmpty() && cumulative.isEmpty()) item {
            EmptyState(Icons.Rounded.TaskAlt, if (model.connected) "Working tree clean" else "No saved changes",
                if (model.connected) "There are no changes to show for this session." else "Connect to load this session's changes.")
        }
        if (working.isNotEmpty()) item {
            SectionLabel("Working tree") { DiffStat(working.sumOf { it.additions }, working.sumOf { it.deletions }) }
        }
        items(working, key = { "working:${it.repository}:${it.path}" }) { file -> ChangeRow(file) { model.selectDiff(file) } }
        if (cumulative.isNotEmpty()) item {
            SectionLabel("Branch changes") { DiffStat(cumulative.sumOf { it.additions }, cumulative.sumOf { it.deletions }) }
        }
        items(cumulative, key = { "branch:${it.repository}:${it.path}" }) { file -> ChangeRow(file) { model.selectDiff(file) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepositoryStatus(status: GitStatus) {
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Source, size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status.repository.ifBlank { "Repository" }, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status.branch.isNotBlank()) Tag(status.branch, Icons.AutoMirrored.Rounded.AltRoute, mono = true)
                    if (status.statusState == "ready") {
                        Tag("↑${status.ahead}  ↓${status.behind}", mono = true)
                    } else Tag("Git ${status.statusState}" + (status.errorCode?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        Icons.Rounded.Warning, container = ForgeTheme.status.warningContainer, content = ForgeTheme.status.warning)
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(file: ChangedFile, onClick: () -> Unit) {
    val (letter, color) = changeBadge(file.status)
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = MaterialTheme.shapes.extraSmall) {
                Text(letter, Modifier.width(24.dp).padding(vertical = 2.dp), style = MaterialTheme.typography.labelLarge,
                    fontFamily = ForgeCodeFont, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Column(Modifier.weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, fontFamily = ForgeCodeFont,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val directory = file.path.substringBeforeLast('/', "")
                Text(listOfNotNull(directory.ifBlank { null }, if (file.staged) "staged" else null).joinToString(" · ").ifBlank { "./" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DiffStat(file.additions, file.deletions)
        }
    }
}

@Composable
private fun changeBadge(status: String): Pair<String, Color> {
    val colors = ForgeTheme.status
    return when (status.lowercase()) {
        "added", "new", "untracked" -> (if (status.lowercase() == "untracked") "U" else "A") to colors.success
        "deleted", "removed" -> "D" to MaterialTheme.colorScheme.error
        "renamed" -> "R" to colors.info
        "copied" -> "C" to colors.info
        "conflicted", "unmerged" -> "!" to colors.warning
        else -> "M" to colors.warning
    }
}

@Composable
private fun FilesPanel(model: WorkspaceController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = model.pendingDownload
        model.pendingDownload = null
        if (uri != null && source != null) scope.launch {
            downloading = true
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = fileBytes(source)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
            }.isSuccess
            downloading = false
            Toast.makeText(context, if (success) "File saved." else "Could not save this file. Try again.", Toast.LENGTH_LONG).show()
        }
    }
    val download: (String) -> Unit = { path ->
        scope.launch {
            downloading = true
            try {
                model.pendingDownload = model.fileForDownload(path)
                save.launch(path.substringAfterLast('/'))
            } catch (e: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                Toast.makeText(context, "Could not download this file. Reconnect and try again.", Toast.LENGTH_LONG).show()
            } finally { downloading = false }
        }
    }
    val file = model.file
    if (file != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::closeFile, enabled = !model.fileSaving) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (model.returnsToChat) "Back to chat" else "Files")
                }
                Icon(fileIcon(file.path), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontFamily = ForgeCodeFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Files outside the workspace (/tmp, ~) are readable but not writable through Kandev.
                    val external = file.path.startsWith('/') || file.path.startsWith("~/")
                    Text(if (external) "${fileSize(file.size)} · ${file.path.substringBeforeLast('/')} · read-only" else fileSize(file.size),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { download(file.path) }, enabled = !downloading) {
                    Icon(Icons.Rounded.Download, "Download file")
                }
                if (model.fileDraft == null && !file.binary && !file.path.startsWith('/') && !file.path.startsWith("~/")) IconButton(onClick = model::editFile,
                    enabled = !file.binary && file.content.length < 256_000) { Icon(Icons.Rounded.Edit, "Edit") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val change = remember(file.path, model.changes) { model.changeFor(file.path) }
            val diff = change?.let { it.diff ?: it.unstagedChange?.diff ?: it.stagedChange?.diff }?.takeIf { it.isNotBlank() }
            var showDiff by remember(file.path, diff != null) { mutableStateOf(diff != null) }
            if (diff != null && model.fileDraft == null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(showDiff, { showDiff = true }, label = { Text("Diff") },
                    leadingIcon = { Icon(Icons.Rounded.Difference, null, Modifier.size(16.dp)) })
                FilterChip(!showDiff, { showDiff = false }, label = { Text("File") },
                    leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(16.dp)) })
                Spacer(Modifier.weight(1f))
                change?.let { DiffStat(it.additions, it.deletions) }
            }
            if (diff != null && showDiff && model.fileDraft == null) CodeLines(diff, diff = true, modifier = Modifier.weight(1f).fillMaxWidth())
            else if (file.path.endsWith(".pdf", ignoreCase = true)) PdfPreview(file, Modifier.weight(1f).fillMaxWidth())
            else if (file.binary && isImagePath(file.path)) ImagePreview(file, Modifier.weight(1f).fillMaxWidth())
            else if (file.binary) EmptyState(Icons.Rounded.DataObject, "Binary file", "${fileSize(file.size)} · preview not available. Use Download to save and open this file.")
            else {
                val content = remember(file) { runCatching { decodeFile(file) }.getOrDefault("Could not decode this file.") }
                val draft = model.fileDraft
                if (draft != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetaItem(Icons.Rounded.EditNote, "Unsaved · kept on this device", Modifier.weight(1f), color = ForgeTheme.status.warning)
                        OutlinedButton(onClick = model::cancelFileEdit, enabled = !model.fileSaving,
                            contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Discard")
                        }
                        Button(onClick = model::saveFile, enabled = model.connected && !model.fileSaving,
                            contentPadding = PaddingValues(horizontal = 14.dp)) {
                            if (model.fileSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Save")
                        }
                    }
                    OutlinedTextField(value = draft, onValueChange = model::updateFileDraft, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = ForgeCodeFont), enabled = !model.fileSaving)
                } else CodeLines(content, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
        return
    }
    val tree = model.tree
    Column(Modifier.fillMaxSize()) {
        Breadcrumbs(model.directory, model::enterDirectory)
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (model.directory.isNotBlank()) item(key = "..") {
                FileRow(Icons.Rounded.DriveFolderUpload, "..", null, MaterialTheme.colorScheme.onSurfaceVariant) {
                    model.enterDirectory(model.directory.substringBeforeLast('/', ""))
                }
            }
            if (tree == null && !model.busy) item {
                if (model.connected) EmptyState(Icons.Rounded.FolderOff, "Folder unavailable", "The workspace may have been cleaned up. Tap refresh to retry.")
                else EmptyState(Icons.Rounded.FolderOff, "Folder not saved", "Connect to browse this workspace.")
            }
            val nodes = tree?.root?.children.orEmpty().sortedWith(compareByDescending<FileNode> { it.directory }.thenBy { it.name.lowercase() })
            if (tree != null && nodes.isEmpty()) item { EmptyState(Icons.Rounded.FolderOpen, "Empty folder", "Nothing here yet.") }
            items(nodes, key = { it.path }) { node ->
                FileRow(if (node.directory) Icons.Rounded.Folder else fileIcon(node.name), node.name,
                    if (node.directory) null else fileSize(node.size),
                    if (node.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onDownload = if (node.directory) null else ({ download(node.path) }), downloadEnabled = !downloading) {
                    if (node.directory) model.enterDirectory(node.path) else model.openFile(node.path)
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(directory: String, onNavigate: (String) -> Unit) {
    val parts = directory.split('/').filter { it.isNotBlank() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = { onNavigate("") }, label = { Text("workspace", fontFamily = ForgeCodeFont) },
            leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(16.dp)) })
        parts.forEachIndexed { index, part ->
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(parts.take(index + 1).joinToString("/")) }, contentPadding = PaddingValues(horizontal = 6.dp),
                enabled = index != parts.lastIndex) { Text(part, fontFamily = ForgeCodeFont, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun FileRow(icon: ImageVector, name: String, detail: String?, tint: Color,
    onDownload: (() -> Unit)? = null, downloadEnabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = ForgeCodeFont,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onDownload != null) IconButton(onClick = onDownload, enabled = downloadEnabled) {
            Icon(Icons.Rounded.Download, "Download $name", Modifier.size(20.dp))
        }
    }
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts", "java", "ts", "tsx", "js", "jsx", "go", "py", "rs", "c", "cpp", "h", "swift", "rb", "php", "cs", "dart" -> Icons.Rounded.Code
    "json", "yaml", "yml", "toml", "xml", "gradle", "properties", "ini", "conf", "env" -> Icons.Rounded.DataObject
    "pdf" -> Icons.Rounded.PictureAsPdf
    "md", "txt", "rst", "adoc" -> Icons.AutoMirrored.Rounded.Article
    "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" -> Icons.Rounded.Image
    "sh", "bash", "zsh", "fish", "ps1", "bat" -> Icons.Rounded.Terminal
    "html", "css", "scss" -> Icons.Rounded.Web
    "lock" -> Icons.Rounded.Lock
    "zip", "tar", "gz", "jar", "apk", "aar" -> Icons.Rounded.Archive
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

internal fun fileSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024))
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}

/** Decode away from the UI thread and bound the bitmap size. */
@Composable
private fun ImagePreview(file: WorkspaceFile, modifier: Modifier) {
    var failed by remember(file) { mutableStateOf(false) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.Default) { decodeImage(fileBytes(file), 2048) }
        failed = value == null
    }
    BitmapPreview(bitmap, failed, file.path, modifier)
}

/** Decodes with power-of-two subsampling so neither side is much over [maxSide]; null if it isn't an image. */
internal fun decodeImage(bytes: ByteArray, maxSide: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })).asImageBitmap()
}.getOrNull()

/** Kept separate so native rendering and resource cleanup can be checked on Android. */
internal fun renderPdfPage(file: WorkspaceFile, cacheDir: java.io.File, page: Int): Pair<Int, androidx.compose.ui.graphics.ImageBitmap> {
    val temp = java.io.File.createTempFile("preview-", ".pdf", cacheDir)
    try {
        temp.writeBytes(fileBytes(file))
        android.os.ParcelFileDescriptor.open(temp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(page).use { pdfPage ->
                    val ratio = 1600f / maxOf(pdfPage.width, pdfPage.height).coerceAtLeast(1)
                    val image = android.graphics.Bitmap.createBitmap(
                        (pdfPage.width * ratio).toInt().coerceAtLeast(1),
                        (pdfPage.height * ratio).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(android.graphics.Color.WHITE)
                        pdfPage.render(image, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return renderer.pageCount to image.asImageBitmap()
                    } catch (e: Exception) { image.recycle(); throw e }
                }
            }
        }
    } finally { temp.delete() }
}

/** Render only the selected page, closing every native handle and deleting the temporary PDF. */
@Composable
private fun PdfPreview(file: WorkspaceFile, modifier: Modifier) {
    val context = LocalContext.current
    var page by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var failed by remember(file, page) { mutableStateOf(false) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file, page) {
        value = null
        val result = withContext(Dispatchers.IO) {
            runCatching { renderPdfPage(file, context.cacheDir, page) }.getOrNull()
        }
        pageCount = result?.first ?: pageCount
        value = result?.second
        failed = result == null
    }
    Column(modifier) {
        if (pageCount > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
            Text("${page + 1} / $pageCount", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { page++ }, enabled = page < pageCount - 1) { Text("Next") }
        }
        BitmapPreview(bitmap, failed, "${file.path}:$page", Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun BitmapPreview(bitmap: androidx.compose.ui.graphics.ImageBitmap?, failed: Boolean, key: String, modifier: Modifier) {
    var scale by remember(key) { mutableStateOf(1f) }
    var offset by remember(key) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = androidx.compose.foundation.gestures.rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
        if (failed) EmptyState(Icons.Rounded.Description, "Preview unavailable", "This file could not be previewed. Use Download to save and open it.")
        else if (bitmap == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else androidx.compose.foundation.Image(bitmap, key.substringAfterLast('/'),
            Modifier.fillMaxSize().padding(8.dp).transformable(transform)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable
internal fun CodeLines(content: String, modifier: Modifier = Modifier, diff: Boolean = false) {
    val lines by produceState<List<String>>(emptyList(), content) { value = withContext(Dispatchers.Default) { content.lines() } }
    val status = ForgeTheme.status
    BoxWithConstraints(modifier) {
        val width = maxWidth.coerceAtLeast(200.dp)
        val contentWidth = (lines.maxOfOrNull { it.length }?.coerceAtMost(500)?.times(7)?.dp?.plus(56.dp) ?: width).coerceAtLeast(width)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(contentWidth).fillMaxHeight(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(lines) { index, line ->
                    val (background, foreground) = when {
                        diff && line.startsWith("@@") -> status.diffHunk to status.info
                        diff && (line.startsWith("+++") || line.startsWith("---")) -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        diff && line.startsWith("+") -> status.diffAdded to MaterialTheme.colorScheme.onSurface
                        diff && line.startsWith("-") -> status.diffRemoved to MaterialTheme.colorScheme.onSurface
                        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                    }
                    Surface(color = background) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                            Text("${index + 1}", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, fontFamily = ForgeCodeFont)
                            SelectionContainer { Text(line.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, fontFamily = ForgeCodeFont,
                                softWrap = false, color = foreground) }
                        }
                    }
                }
            }
        }
    }
}
