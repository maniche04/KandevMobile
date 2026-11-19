package ca.mslab.forge.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.mslab.forge.notify.AgentNotifications
import ca.mslab.forge.push.PushEntry
import ca.mslab.forge.push.PushLog
import ca.mslab.forge.push.RelayClient
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/** On-device notifications: permission and Android 16 Live Updates. */
@Composable
internal fun NotificationSettings() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AgentNotifications.canPost(context)) }
    var promoted by remember { mutableStateOf(canPromote(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = AgentNotifications.canPost(context); promoted = canPromote(context)
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    SettingsCard {
        SettingsRow(if (allowed) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
            "Agent notifications", if (allowed) "Live progress, needs-you alerts and finished turns" else "Off — you won't hear from your agents",
            ok = allowed) {
            if (!allowed) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) request.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Allow") }
        }
        if (Build.VERSION.SDK_INT >= 36) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Bolt, "Live Updates",
                if (promoted) "Running agents show in the status bar and on the lock screen" else "Allow to pin agent progress to the status bar",
                ok = promoted) {
                if (!promoted) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Allow") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsRow(Icons.Rounded.Tune, "Channels", "Choose sound and priority per alert type", ok = true) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Open") }
        }
    }
}

private fun canPromote(context: android.content.Context) =
    Build.VERSION.SDK_INT >= 36 && context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

/**
 * Offline alerts: forge-relay delivers encrypted alerts directly through FCM.
 * Existing UnifiedPush pairings continue until the phone is paired again.
 */
@Composable
internal fun OfflineAlertSettings(serverUrl: String) {
    val context = LocalContext.current
    val relay = remember { RelayClient.get(context) }
    val status by relay.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(status.relayUrl.ifBlank { relay.defaultRelayUrl(serverUrl) }) }
    var key by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(!status.registered) }
    val fcmAvailable = relay.fcmAvailable
    LifecycleResumeEffect(Unit) { relay.refresh(); onPauseOrDispose { } }
    SettingsCard {
        SettingsRow(if (status.registered) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, "Alerts when away",
            when {
                status.registered -> "Paired · arrives via ${status.distributor?.substringAfterLast('.') ?: "UnifiedPush"} even without Tailscale"
                !fcmAvailable -> "Needs Google Play services and a Firebase-configured build"
                else -> "Not paired yet"
            }, ok = status.registered) {}
        if (status.registered && status.confirmedAt > 0) Text("Relay confirmed this phone " +
            android.text.format.DateUtils.getRelativeTimeSpanString(status.confirmedAt, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.message?.let { InfoNotice(it, icon = Icons.Rounded.Info) }
        Text("Receives encrypted alerts directly through Google push. No ntfy subscription is needed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (editing || !status.registered) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Relay address") },
                leadingIcon = { Icon(Icons.Rounded.Router, null) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ForgeCodeFont),
                supportingText = { Text("forge-relay on your Kandev server, port 38430") })
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Pairing key") },
                leadingIcon = { Icon(Icons.Rounded.Key, null) }, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(if (status.hasKey) "Leave blank to keep the saved pairing key" else "Shown in the relay log: journalctl --user -u forge-relay") })
            Button(onClick = {
                working = true
                scope.launch {
                    try {
                        relay.pairFcm(url, key)
                        editing = false
                    } catch (e: Exception) {
                        relay.setMessage("Pairing failed: ${e.message}. The relay must be reachable over your home network or Tailscale.")
                    } finally { working = false }
                }
            }, enabled = !working && url.isNotBlank() && (key.isNotBlank() || status.hasKey) && fcmAvailable, modifier = Modifier.fillMaxWidth()) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else { Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Pair this phone") }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                working = true
                scope.launch {
                    relay.sendTest().onFailure { relay.setMessage("Test failed: ${it.message}") }
                        .onSuccess { relay.setMessage("Test sent. It should arrive in a few seconds.") }
                    working = false
                }
            }, enabled = !working, modifier = Modifier.weight(1f)) { Text("Send test") }
            TextButton(onClick = { editing = true }) { Text("Change") }
            TextButton(onClick = {
                scope.launch {
                    try {
                        relay.unregister()
                        UnifiedPush.unregister(context, "default")
                        editing = true
                    } catch (e: Exception) { relay.setMessage("Unpair failed: ${e.message}") }
                }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Unpair") }
        }
    }
}

/** Settings › Delivery log: each alert, how long push took, its priority and what the phone did with it. */
@Composable
internal fun DeliveryLog() {
    val context = LocalContext.current
    val entries by remember { PushLog.load(context) }.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SettingsRow(Icons.Rounded.History, "Delivery log",
            entries.firstOrNull()?.let { "Last: ${it.outcome}" } ?: "No alerts received yet",
            ok = entries.isNotEmpty(), onClick = { open = !open })
        if (open) {
            Text("“high→normal” means Google lowered the priority, so a sleeping phone may get it late.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEach { DeliveryRow(it) }
            if (entries.isNotEmpty()) TextButton(onClick = { PushLog.clear(context) }) { Text("Clear") }
        }
    }
}

@Composable
private fun DeliveryRow(entry: PushEntry) {
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
        .format(java.util.Date(entry.at))
    val meta = listOfNotNull(time, entry.kind.replace('_', ' '), entry.source, entry.priority,
        entry.delayMs?.let { "%.1f s".format(it / 1000.0) }).joinToString(" · ")
    Column {
        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(listOf(entry.title, entry.outcome).filter { it.isNotBlank() }.joinToString(" — "),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Icon badge, title and one line of detail, with an optional trailing action; tappable when [onClick] is set. */
@Composable
internal fun SettingsRow(icon: ImageVector, title: String, detail: String, ok: Boolean = false,
    onClick: (() -> Unit)? = null, action: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, size = 34.dp,
            container = if (ok) ForgeTheme.status.successContainer else scheme.surfaceContainerHigh,
            content = if (ok) ForgeTheme.status.success else scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        action()
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
    }
}
