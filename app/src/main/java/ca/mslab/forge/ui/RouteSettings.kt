package ca.mslab.forge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.routing.RouteMode
import ca.mslab.forge.ui.theme.ForgeCodeFont

private fun RouteMode.title() = when (this) {
    RouteMode.Automatic -> "Automatic"; RouteMode.Local -> "Local"; RouteMode.Tailscale -> "Tailscale"; RouteMode.Public -> "Cloudflare"
}

/** Route summary row; editing happens in a sheet so the settings list stays short. */
@Composable
internal fun RouteSettings(model: ConnectionViewModel) {
    val profile = model.hub.profile.value ?: return
    val saved by profile.routes.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val extra = listOfNotNull(saved.local?.let { "local" }, saved.tailscale?.let { "Tailscale" })
    SettingsRow(Icons.AutoMirrored.Rounded.AltRoute, "Connection route",
        "${saved.mode.title()} · " + if (extra.isEmpty()) "primary address only" else "primary + ${extra.joinToString(" + ")}",
        onClick = { editing = true })
    if (editing) RouteSheet(model, onDismiss = { editing = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(model: ConnectionViewModel, onDismiss: () -> Unit) {
    val profile = model.hub.profile.value ?: return
    val saved by profile.routes.settings.collectAsStateWithLifecycle()
    var local by remember(saved) { mutableStateOf(saved.local?.url.orEmpty()) }
    var localToken by remember(saved) { mutableStateOf(saved.local?.token.orEmpty()) }
    var tailscale by remember(saved) { mutableStateOf(saved.tailscale?.url.orEmpty()) }
    var tailscaleToken by remember(saved) { mutableStateOf(saved.tailscale?.token.orEmpty()) }
    var mode by remember(saved) { mutableStateOf(saved.mode) }
    val modes = listOfNotNull(RouteMode.Automatic, RouteMode.Local, RouteMode.Tailscale, RouteMode.Public.takeIf { saved.access != null })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Connection routes", style = MaterialTheme.typography.titleLarge)
                Text("Automatic tries every saved route at once and uses the first that answers. A slow local route never holds up the others.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, option ->
                    SegmentedButton(selected = mode == option, onClick = { mode = option },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size), icon = {}) {
                        Text(option.title(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            RouteGroup(Icons.Rounded.Dns, "Primary", "Always available") {
                Text(saved.url.removeSuffix("/"), style = MaterialTheme.typography.bodyMedium, fontFamily = ForgeCodeFont,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RouteGroup(Icons.Rounded.Home, "Local", "Your home network") {
                RouteField(local, { local = it }, "Address", "http://192.168.1.10:38429")
                RouteField(localToken, { localToken = it }, "API token (optional)", secret = true)
            }
            RouteGroup(Icons.Rounded.VpnKey, "Tailscale", "Your tailnet") {
                RouteField(tailscale, { tailscale = it }, "Address", "http://100.x.x.x:38429")
                RouteField(tailscaleToken, { tailscaleToken = it }, "API token (optional)", secret = true)
            }
            InfoNotice("Use direct HTTP only on networks you trust. Cloudflare Access credentials are never sent to these addresses. Routes share history and drafts.",
                icon = Icons.Rounded.Shield)
            Button(onClick = { model.saveRoutes(local, localToken, tailscale, tailscaleToken, mode); onDismiss() },
                enabled = !model.busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.small) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Apply routes")
            }
        }
    }
}

@Composable
private fun RouteGroup(icon: ImageVector, title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
private fun RouteField(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, secret: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, fontFamily = ForgeCodeFont) } }, shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (secret) null else ForgeCodeFont),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri))
}
