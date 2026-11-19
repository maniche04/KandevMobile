package ca.mslab.forge.ui

import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ca.mslab.forge.notify.AgentNotifications
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.mslab.forge.BuildConfig
import ca.mslab.forge.R
import ca.mslab.forge.data.TARGET_KANDEV_VERSION
import ca.mslab.forge.data.Workspace
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme

private enum class Screen { Splash, Connect, Tasks, Settings, Task }

@Composable
fun ForgeApp(model: ConnectionViewModel = viewModel()) {
    val snapshot = model.snapshot
    val context = LocalContext.current
    val markdown = remember { MarkdownRenderer(context) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val taskListState = rememberLazyListState()
    DisposableEffect(model) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { model.networkAvailable() }
            override fun onLost(network: Network) { model.networkLost() }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    LifecycleStartEffect(model) {
        model.setForeground(true)
        onStopOrDispose { model.setForeground(false) }
    }
    val screen = when {
        snapshot == null && model.restoring -> Screen.Splash
        snapshot == null -> Screen.Connect
        model.task != null -> Screen.Task
        settingsOpen -> Screen.Settings
        else -> Screen.Tasks
    }
    // Notification taps open their task once the cached profile is available.
    val deepLink by model.hub.deepLink.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink, snapshot != null) {
        val link = deepLink ?: return@LaunchedEffect
        if (snapshot == null) return@LaunchedEffect
        settingsOpen = false
        model.openTaskById(link.taskId, link.review, link.sessionId)
        model.hub.deepLink.value = null
    }
    // Alerts are suppressed for the task you are looking at.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { resumed = true; onPauseOrDispose { resumed = false } }
    LaunchedEffect(screen, model.task?.id, resumed) {
        model.hub.visibleTask.value = if (resumed && screen == Screen.Task) model.task?.id else null
    }
    // Ask for notification permission once, after the first successful connection.
    val prefs = remember { context.getSharedPreferences("forge-ui", android.content.Context.MODE_PRIVATE) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(screen) {
        if (screen == Screen.Tasks && android.os.Build.VERSION.SDK_INT >= 33 && !AgentNotifications.canPost(context) &&
            !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    BackHandler(enabled = screen == Screen.Task, onBack = model::back)
    BackHandler(enabled = screen == Screen.Settings) { settingsOpen = false }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val agentIcon = remember(model, dark) { { id: String? -> model.agentIcon(id, dark) } }
    // Tablets and unfolded foldables: forms stay a comfortable column, while the board and the task workspace
    // (diffs, files, terminal) use the whole width; the chat itself keeps a readable measure inside it.
    val maxWidth = when (screen) {
        Screen.Task -> Dp.Unspecified
        Screen.Tasks -> if (model.homeView == HomeView.Board) Dp.Unspecified else 960.dp
        else -> 720.dp
    }
    CompositionLocalProvider(LocalMarkdown provides markdown, LocalAgentIcon provides agentIcon) {
        ForgeScreen(maxWidth) {
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { current ->
                when (current) {
                    Screen.Splash -> Splash()
                    Screen.Connect -> ConnectionForm(
                        serverUrl = model.serverUrl, token = model.token,
                        busy = model.busy, error = model.error,
                        onServerChange = model::editServer, onTokenChange = model::editToken,
                        onConnect = model::connect, onRemoteApproved = model::connectRemote,
                    )
                    Screen.Task -> TaskConversationScreen(model)
                    Screen.Settings -> SettingsScreen(model, onClose = { settingsOpen = false })
                    Screen.Tasks -> TaskListScreen(model, taskListState, onSettings = { settingsOpen = true })
                }
            }
        }
    }
}

@Composable
private fun ForgeScreen(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun Splash() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.forge_mark), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text("Reconnecting to Kandev…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionForm(
    serverUrl: String, token: String, busy: Boolean, error: String?,
    onServerChange: (String) -> Unit, onTokenChange: (String) -> Unit, onConnect: () -> Unit,
    onRemoteApproved: (ca.mslab.forge.security.RemoteEnrollment) -> Unit = {},
) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.forge_mark), contentDescription = null, modifier = Modifier.size(64.dp))
                Text("Forge", style = MaterialTheme.typography.headlineLarge)
                Text("Your Kandev tasks, agents and code — in your pocket.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Live chat", Icons.Rounded.Forum)
                Tag("Diffs", Icons.Rounded.Difference)
                Tag("Files", Icons.Rounded.FolderOpen)
                Tag("Terminal", Icons.Rounded.Terminal)
            }
        }
        item { RemoteAccessSettings(onRemoteApproved) }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Rounded.Dns)
                        Column {
                            Text("Connect your server", style = MaterialTheme.typography.titleMedium)
                            Text("Kandev v$TARGET_KANDEV_VERSION", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedTextField(
                        value = serverUrl, onValueChange = onServerChange,
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text("Server address") },
                        placeholder = { Text("https://forge.example.com") },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = token, onValueChange = onTokenChange,
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                        label = { Text("API token (optional)") },
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (showToken) "Hide token" else "Show token")
                            }
                        },
                        shape = MaterialTheme.shapes.small,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Only needed when authentication is enabled. Use HTTPS for tokens.") },
                    )
                    if (error != null) ErrorNotice(error)
                    Button(
                        onClick = onConnect, enabled = !busy && serverUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        else {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
        item {
            InfoNotice("Saved securely on this device with Android Keystore. Disconnect from Settings to forget it.",
                icon = Icons.Rounded.Lock)
        }
    }
}

@Composable
internal fun WorkspaceRow(workspace: Workspace, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Workspaces,
                container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(workspace.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (workspace.taskPrefix.isNotBlank()) Tag(workspace.taskPrefix, mono = true)
                }
                workspace.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Preview(name = "Connect – light", showBackground = true)
@Composable
private fun ConnectPreview() {
    ForgeTheme(darkTheme = false) {
        ForgeScreen { ConnectionForm("", "", false, null, {}, {}, {}) }
    }
}
