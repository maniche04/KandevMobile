package ca.mslab.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.AgentSession
import ca.mslab.forge.data.QueuedMessage
import ca.mslab.forge.data.SessionAction
import ca.mslab.forge.ui.theme.ForgeTheme

/**
 * Follow-ups waiting for the current turn to end. Each can be sent now — interrupting the turn and
 * delivering that message in its place (the web's steer) — edited, reordered or dropped.
 */
@Composable
internal fun QueuePanel(model: ConnectionViewModel) {
    val queue = model.queue
    val status = ForgeTheme.status
    val entries = remember(queue.status) { queue.status.entries.sortedBy { it.position } }
    var expanded by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    val busyTurn = model.session?.state in setOf("RUNNING", "STARTING")
    Surface(color = status.infoContainer.copy(alpha = 0.45f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = status.info)
                Text("${queue.count} queued" + if (busyTurn) " · runs when this turn ends" else "",
                    style = MaterialTheme.typography.labelLarge, color = status.info, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entries.size > 1) TextButton(onClick = { queue.sendNow(null) }, enabled = model.live && "all" !in queue.working,
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text("Send all now", style = MaterialTheme.typography.labelMedium)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "Queue options", Modifier.size(18.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear queue") }, leadingIcon = { Icon(Icons.Rounded.ClearAll, null) },
                            enabled = model.live && "all" !in queue.working, onClick = { menu = false; queue.clear() })
                    }
                }
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, if (expanded) "Collapse" else "Expand",
                    Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        QueueRow(model, entry, entries.indexOf(entry), entries.size) { editing = entry }
                    }
                }
            }
        }
    }
    editing?.let { entry -> QueueEditDialog(entry, onSave = { queue.edit(entry.id, it); editing = null }) { editing = null } }
}

@Composable
private fun QueueRow(model: ConnectionViewModel, entry: QueuedMessage, index: Int, total: Int, onEdit: () -> Unit) {
    val queue = model.queue
    val working = entry.id in queue.working
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(entry.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "(attachments)" },
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(enabled = !working && entry.attachments.isNullOrEmpty(), onClick = onEdit).padding(vertical = 6.dp))
        if (working) CircularProgressIndicator(Modifier.padding(horizontal = 10.dp).size(16.dp), strokeWidth = 2.dp)
        else {
            // The steer: interrupt this turn and deliver this message now.
            FilledTonalButton(onClick = { queue.sendNow(entry.id) }, enabled = model.live, modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp)); Spacer(Modifier.width(2.dp))
                Text("Send now", style = MaterialTheme.typography.labelMedium)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "Queued message options", Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    if (entry.attachments.isNullOrEmpty()) DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        enabled = model.live, onClick = { menu = false; onEdit() })
                    if (index > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                        enabled = model.live, onClick = { menu = false; queue.move(entry.id, -1) })
                    if (index < total - 1) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                        enabled = model.live, onClick = { menu = false; queue.move(entry.id, 1) })
                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        enabled = model.live, onClick = { menu = false; queue.remove(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun QueueEditDialog(entry: QueuedMessage, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.content) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit queued message") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), maxLines = 10) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text != entry.content) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Takes the composer's place once the session has ended (stopped, failed or completed): the ways back,
 * like the web's stopped-session banner. Resume keeps the conversation; Start fresh drops the old context.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionEndedBanner(model: ConnectionViewModel, session: AgentSession) {
    val controller = model.sessionActions
    val status = ForgeTheme.status
    var newSession by remember { mutableStateOf(false) }
    val failed = session.state == "FAILED"
    val (title, detail) = when (session.state) {
        "FAILED" -> "The agent failed" to session.errorMessage.lineSequence().firstOrNull { it.isNotBlank() }?.take(240)
        "CANCELLED" -> "The agent was stopped" to "Resume to pick up where it left off."
        else -> "This session is complete" to "Resume to continue the conversation, or start a new session."
    }
    val enabled = controller.enabled && !model.sending
    Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (failed) Icons.Rounded.ErrorOutline else if (session.state == "COMPLETED") Icons.Rounded.TaskAlt else Icons.Rounded.StopCircle,
                    null, Modifier.size(20.dp), tint = if (failed) MaterialTheme.colorScheme.error else status.info)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                if (controller.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (controller.offers(SessionAction.Resume)) Button(onClick = { controller.perform(SessionAction.Resume) }, enabled = enabled) {
                    Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume")
                }
                if (controller.offers(SessionAction.NewBranch)) FilledTonalButton(onClick = { controller.perform(SessionAction.NewBranch) }, enabled = enabled) {
                    Text("Continue on a new branch")
                }
                if (controller.offers(SessionAction.FreshStart)) OutlinedButton(onClick = { controller.perform(SessionAction.FreshStart) }, enabled = enabled) {
                    Text("Start fresh")
                }
                if (session.state == "COMPLETED") OutlinedButton(onClick = { model.clearCreateError(); model.loadLaunchOptions(); newSession = true },
                    enabled = model.live) { Text("New session") }
            }
            if (!model.live) Text("Connect to resume.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (newSession) NewSessionSheet(model) { newSession = false }
}
