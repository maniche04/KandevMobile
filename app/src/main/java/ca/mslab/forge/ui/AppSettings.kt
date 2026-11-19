package ca.mslab.forge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.mslab.forge.BuildConfig
import ca.mslab.forge.data.AppUpdates
import ca.mslab.forge.ui.theme.Appearance
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Light, dark, or whatever the phone uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSettings() {
    val appearance = Appearance.get(LocalContext.current)
    val mode by appearance.mode.collectAsStateWithLifecycle()
    SettingsCard {
        SettingsRow(when (mode) { ThemeMode.System -> Icons.Rounded.BrightnessAuto; ThemeMode.Light -> Icons.Rounded.LightMode
            ThemeMode.Dark -> Icons.Rounded.DarkMode }, "Theme",
            if (mode == ThemeMode.System) "Follows the phone's light or dark setting" else "Always ${mode.label.lowercase()}")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = mode == option, onClick = { appearance.choose(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)) { Text(option.label) }
            }
        }
    }
}

/** This app's own updates, published with `scripts/publish-apk.sh`: check, download, verify, install. */
@Composable
internal fun AppUpdateRow() {
    val context = LocalContext.current
    val updates = remember { AppUpdates(context) }
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(updates.address) }
    var editing by rememberSaveable { mutableStateOf(updates.address.isBlank()) }
    var release by remember { mutableStateOf<AppUpdates.Release?>(null) }
    var line by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val current = "${BuildConfig.VERSION_NAME} (${updates.installedCode})"
    fun check() {
        if (updates.address.isBlank()) { line = "Set the download directory to check for updates"; return }
        busy = true; release = null; line = "Checking…"
        scope.launch {
            runCatching { updates.check() }
                .onSuccess { r ->
                    release = r
                    line = if (r != null) "${r.versionName} is ready" + r.notes.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                        else "$current · up to date"
                }
                .onFailure { line = "$current · couldn't check: ${it.message ?: "no answer"}" }
            busy = false
        }
    }
    fun install(r: AppUpdates.Release) {
        if (!updates.canInstall()) { line = "Allow Forge to install apps, then tap Install again"; updates.openInstallPermission(); return }
        busy = true
        scope.launch {
            runCatching { updates.download(r) { p -> line = "Downloading ${r.versionName}… ${(p * 100).toInt()}%" } }
                .onSuccess { line = "Verified · opening the installer"; updates.install(it) }
                .onFailure { line = it.message ?: "Couldn't download it" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { check() }
    SettingsRow(Icons.Rounded.SystemUpdate, if (release != null) "Update available" else "App updates",
        line.ifBlank { current }, ok = release != null) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            release != null -> FilledTonalButton(onClick = { install(release!!) }) { Text("Install") }
            else -> TextButton(onClick = ::check) { Text("Check") }
        }
    }
    if (editing) {
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Update directory") },
            leadingIcon = { Icon(Icons.Rounded.Download, null) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ForgeCodeFont),
            supportingText = { Text("A GitHub …/releases/latest/download/ address, or where publish-apk.sh copies the APK and ${context.packageName}.json") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val ok = address.isBlank() || runCatching { AppUpdates.directory(address) }
                    .onFailure { line = it.message.orEmpty() }.isSuccess
                if (ok) { updates.address = address; editing = address.isBlank(); check() }
            }, enabled = !busy) { Text("Save") }
            if (updates.address.isNotBlank()) TextButton(onClick = { address = updates.address; editing = false }) { Text("Cancel") }
        }
    } else TextButton(onClick = { editing = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(updates.address.removeSuffix("/"), style = MaterialTheme.typography.bodySmall, fontFamily = ForgeCodeFont, maxLines = 1)
    }
}
