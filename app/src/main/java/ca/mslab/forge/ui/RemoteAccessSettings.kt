package ca.mslab.forge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ca.mslab.forge.security.RemoteEnrollment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks a Forge connection file and parses it; the caller decides what to do with the approved enrollment. */
@Stable
internal class ConnectionImport {
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var launch: () -> Unit = {}
}

@Composable
internal fun rememberConnectionImport(onApproved: (RemoteEnrollment) -> Unit): ConnectionImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val import = remember { ConnectionImport() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            import.busy = true
            try {
                val approved = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(65537)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                    require(bytes.size <= 65536) { "Connection file is too large." }
                    RemoteEnrollment.parse(bytes.toString(Charsets.UTF_8))
                }
                import.notice = "Connection imported. Delete the transfer copy after connecting."
                onApproved(approved)
            } catch (_: Exception) { import.notice = "Could not import this connection. Ask your server administrator for a new Forge connection file." }
            import.busy = false
        }
    }
    import.launch = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
    return import
}

/** Remote-access importer as a settings row: what it does on the left, one action on the right. */
@Composable
internal fun RemoteAccessRow(import: ConnectionImport, configured: Boolean) {
    SettingsRow(Icons.Rounded.VpnLock, "Secure remote access",
        if (configured) "Cloudflare Access is set up. Import a new file to replace it."
        else "Connect from anywhere without a VPN using a Forge connection file",
        ok = configured) {
        FilledTonalButton(onClick = import.launch, enabled = !import.busy, contentPadding = PaddingValues(horizontal = 14.dp)) {
            if (import.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Import")
        }
    }
    import.notice?.let { InfoNotice(it, icon = Icons.Rounded.Info) }
}

/** Connect screen: the importer on its own card. */
@Composable
internal fun RemoteAccessSettings(onApproved: (RemoteEnrollment) -> Unit) {
    val import = rememberConnectionImport(onApproved)
    SettingsCard {
        RemoteAccessRow(import, configured = false)
        Text("Each phone gets its own revocable file. Credentials are encrypted on this device.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
