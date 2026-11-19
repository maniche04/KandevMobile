package ca.mslab.forge.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import ca.mslab.forge.data.TerminalInfo
import ca.mslab.forge.data.WorkspaceController
import java.io.ByteArrayInputStream
import java.util.Base64

private class TerminalBridge(private val post: Handler, private val model: WorkspaceController, private val view: WebView) {
    @JavascriptInterface fun input(value: String) { post.post { model.terminal.input(value) } }
    @JavascriptInterface fun resize(cols: Int, rows: Int) { post.post { model.terminal.resize(cols, rows) } }
    @JavascriptInterface fun drained(sequence: Long) { post.post { model.terminal.drained(sequence) } }
    @JavascriptInterface fun ready() { post.post {
        model.terminal.bindRenderer { seq, bytes, reset ->
            val base64 = Base64.getEncoder().encodeToString(bytes)
            view.evaluateJavascript("window.forgeWrite($seq,'$base64',$reset)", null)
        }
        view.evaluateJavascript("window.forgeEnabled(${model.terminal.connected})", null)
    } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TerminalPanel(model: WorkspaceController) {
    val context = LocalContext.current
    val terminal = model.terminal
    var terminalView by remember { mutableStateOf<WebView?>(null) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AssistChip(onClick = { menu = true }, label = {
                    Text(model.terminals.indexOfFirst { it.id == terminal.terminalId }.takeIf { it >= 0 }?.let { index -> terminalLabel(model.terminals[index], index) } ?: "Choose terminal",
                        fontFamily = ForgeCodeFont)
                }, leadingIcon = {
                    Box(Modifier.size(8.dp).background(if (terminal.connected) ForgeTheme.status.success else MaterialTheme.colorScheme.outline, CircleShape))
                }, trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    model.terminals.forEachIndexed { index, info ->
                        DropdownMenuItem(text = { Text(terminalLabel(info, index), fontFamily = ForgeCodeFont) }, enabled = model.connected,
                            leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                            trailingIcon = { if (info.id == terminal.terminalId) Icon(Icons.Rounded.Check, "Current") },
                            onClick = { menu = false; model.connectTerminal(info) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("New terminal") }, enabled = model.connected && !model.busy,
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { menu = false; model.createTerminal() })
                }
            }
            IconButton(onClick = model::createTerminal, enabled = model.connected && !model.busy) { Icon(Icons.Rounded.Add, "New terminal") }
            IconButton(onClick = { terminal.resume() }, enabled = model.connected && terminal.terminalId != null && !terminal.connected) {
                Icon(Icons.Rounded.Refresh, "Reconnect terminal")
            }
        }
        Text(terminal.status, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = {
            WebView(context).apply {
                setBackgroundColor(Color.rgb(27, 25, 23))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.domStorageEnabled = false; settings.cacheMode = WebSettings.LOAD_NO_CACHE
                val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path?.startsWith("/assets/terminal/") == true) {
                            loader.shouldInterceptRequest(uri)?.let { return it }
                        }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))
                    }
                }
                addJavascriptInterface(TerminalBridge(Handler(Looper.getMainLooper()), model, this), "ForgeTerminal")
                terminalView = this
                loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
            }
        }, update = { it.evaluateJavascript("window.forgeEnabled && window.forgeEnabled(${terminal.connected})", null) })
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Ctrl C" to "\u0003", "Ctrl D" to "\u0004", "Esc" to "\u001b", "Tab" to "\t",
                "←" to "\u001b[D", "↑" to "\u001b[A", "↓" to "\u001b[B", "→" to "\u001b[C",
                "Home" to "\u001b[H", "End" to "\u001b[F", "|" to "|", "/" to "/").forEach { (label, value) ->
                FilledTonalButton(onClick = { terminal.input(value) }, enabled = terminal.connected,
                    shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label, fontFamily = ForgeCodeFont, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    DisposableEffect(model) {
        onDispose {
            terminal.unbindRenderer()
            terminalView?.apply { removeJavascriptInterface("ForgeTerminal"); destroy() }
            terminalView = null
        }
    }
}

private fun terminalLabel(info: TerminalInfo, index: Int): String =
    if (info.name.isBlank() || info.name == "Terminal") "Terminal ${index + 1}" else info.name
