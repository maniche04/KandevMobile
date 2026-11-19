package ca.mslab.forge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.mslab.forge.BuildConfig
import ca.mslab.forge.data.ConnectionSnapshot
import ca.mslab.forge.data.TARGET_KANDEV_VERSION
import ca.mslab.forge.data.sync.LinkState
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme

/** Connection, workspace selection, notifications and the device profile. */
@Composable
internal fun SettingsScreen(model: ConnectionViewModel, onClose: () -> Unit) {
    val snapshot = model.snapshot ?: return
    var confirmDisconnect by remember { mutableStateOf(false) }
    val remote = rememberConnectionImport { enrollment -> model.connectRemote(enrollment); onClose() }
    val profile by model.hub.profile.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ForgeTopBar("Settings", onBack = onClose) {
            IconButton(onClick = model::refreshWorkspaces, enabled = !model.busy) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ConnectionHeader(model, snapshot) }
            model.error?.let { item { ErrorNotice(it) } }
            item { SectionLabel("Connection") }
            item {
                SettingsCard {
                    RouteSettings(model)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RemoteAccessRow(remote, configured = profile?.routes?.config?.access != null)
                }
            }
            item { SectionLabel("Workspace") }
            if (snapshot.workspaces.isEmpty()) item {
                EmptyState(Icons.Rounded.Workspaces, "No workspaces", "Create a workspace in Kandev to get started.")
            }
            items(snapshot.workspaces, key = { it.id }) { workspace ->
                WorkspaceRow(workspace, selected = workspace.id == model.workspace?.id) {
                    if (workspace.id != model.workspace?.id) model.openWorkspace(workspace)
                    onClose()
                }
            }
            item { SectionLabel("Appearance") }
            item { AppearanceSettings() }
            item { SectionLabel("Notifications") }
            item { NotificationSettings() }
            item { SectionLabel("Alerts when away") }
            item { OfflineAlertSettings(model.hub.profile.value?.routes?.config?.local?.url ?: model.serverUrl) }
            item { DeliveryLog() }
            item { SectionLabel("About") }
            item {
                SettingsCard {
                    KeyValue(Icons.Rounded.PhoneAndroid, "Forge", BuildConfig.VERSION_NAME)
                    KeyValue(Icons.Rounded.Verified, "Works with", "Kandev v$TARGET_KANDEV_VERSION+")
                    KeyValue(Icons.Rounded.Lock, "Profile storage", "Android Keystore")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AppUpdateRow()
                }
            }
            item {
                OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.small) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect and forget server")
                }
            }
        }
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
        title = { Text("Disconnect?") },
        text = { Text("This forgets the server profile and removes cached tasks, conversations, drafts and attachments from this device.") },
        confirmButton = {
            TextButton(onClick = { confirmDisconnect = false; onClose(); model.disconnect() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Disconnect") }
        },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
    )
}

/** Live/connecting/offline at a glance: which route, which address, which server. */
@Composable
private fun ConnectionHeader(model: ConnectionViewModel, snapshot: ConnectionSnapshot) {
    val link by model.linkState.collectAsStateWithLifecycle()
    val status = ForgeTheme.status
    val (title, icon, color, container) = when (link) {
        LinkState.Live -> LinkLook("Connected", Icons.Rounded.CloudDone, status.success, status.successContainer)
        LinkState.Connecting -> LinkLook("Connecting…", Icons.Rounded.CloudSync, status.info, status.infoContainer)
        LinkState.Offline -> LinkLook("Offline", Icons.Rounded.CloudOff, status.warning, status.warningContainer)
    }
    val address = (if (link == LinkState.Live) model.routeAddress else model.serverUrl).removeSuffix("/")
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(icon, size = 48.dp, container = container, content = color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseDot(color, pulsing = link != LinkState.Offline)
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (link == LinkState.Offline) "Showing saved content" else "via ${model.routeLabel}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (link == LinkState.Offline) FilledTonalButton(onClick = model::networkAvailable,
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Retry") }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = ForgeCodeFont,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Kandev", snapshot.server.version, Modifier.weight(1f))
                Stat("Commit", snapshot.server.commit.take(7), Modifier.weight(1f))
                Stat("Workspaces", "${snapshot.workspaces.size}", Modifier.weight(1f))
            }
        }
    }
}

private data class LinkLook(val title: String, val icon: ImageVector, val color: androidx.compose.ui.graphics.Color,
    val container: androidx.compose.ui.graphics.Color)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, fontFamily = ForgeCodeFont,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyValue(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = ForgeCodeFont, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
