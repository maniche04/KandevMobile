package ca.mslab.forge.data

import androidx.compose.runtime.*
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal fun terminalResize(cols: Int, rows: Int): ByteString {
    require(cols in 2..500 && rows in 1..500)
    return (byteArrayOf(1) + "{\"cols\":$cols,\"rows\":$rows}".toByteArray()).toByteString()
}

/** One PTY attachment. Keystrokes are never retained or replayed. */
internal class TerminalController(
    private val scope: CoroutineScope,
    private val api: () -> KandevApi?,
    private val store: () -> SnapshotStore? = { null },
) {
    var status by mutableStateOf("Choose a terminal or create one"); private set
    var connected by mutableStateOf(false); private set
    var terminalId by mutableStateOf<String?>(null); private set
    private var environment = ""
    private var requested = false
    private var runner: Job? = null
    private var socket: WebSocket? = null
    private var cols = 80
    private var rows = 24
    private val output = ByteRing(512 * 1024)
    private var renderer: ((Long, ByteArray, Boolean) -> Unit)? = null
    private var sequence = 0L
    private val acknowledgements = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Serializable private data class Replay(val data: String)

    fun bindRenderer(render: (Long, ByteArray, Boolean) -> Unit) {
        renderer = render
        render(++sequence, output.snapshot(), true)
    }
    fun unbindRenderer() {
        renderer = null
        acknowledgements.values.forEach { it.complete(Unit) }
        acknowledgements.clear()
    }
    fun drained(id: Long) { acknowledgements.remove(id)?.complete(Unit) }
    fun networkAvailable() { wake.trySend(Unit) }
    fun routeChanged() {
        // The PTY remains on the server. Reattach without replaying keystrokes or commands.
        if (runner?.isActive == true) terminalId?.let { connect(environment, it) }
    }

    fun connect(environmentId: String, id: String) {
        val changed = terminalId != id || environment != environmentId
        pause()
        terminalId = id; environment = environmentId; requested = true
        if (changed) { output.clear(); renderer?.invoke(++sequence, output.snapshot(), true) }
        runner = scope.launch {
            if (changed) store()?.read("terminal:$environmentId:$id", Replay.serializer())?.let {
                output.clear(); output.append(runCatching { Base64.getDecoder().decode(it.value.data) }.getOrDefault(byteArrayOf()))
                renderer?.invoke(++sequence, output.snapshot(), true)
            }
            runConnection(environmentId, id)
        }
    }

    fun resume() {
        val id = terminalId ?: return
        if (requested && runner?.isActive != true) connect(environment, id)
    }
    fun pause() {
        runner?.cancel(); runner = null
        socket?.cancel(); socket = null; connected = false
        if (terminalId != null) status = "Paused · terminal stays open on the server"
        val id = terminalId; val env = environment; val bytes = output.snapshot(); val storage = store()
        if (id != null && bytes.isNotEmpty()) scope.launch {
            runCatching { storage?.write("terminal:$env:$id", Replay.serializer(), Replay(Base64.getEncoder().encodeToString(bytes))) }
        }
    }
    fun stop() {
        pause(); requested = false; terminalId = null; environment = ""; output.clear()
        status = "Choose a terminal or create one"
        renderer?.invoke(++sequence, output.snapshot(), true)
    }
    fun input(value: String) {
        if (!connected || value.isEmpty()) return
        // The release's PTY protocol sends raw UTF-8 binary input, not JSON commands.
        if (socket?.send(value.toByteArray().toByteString()) != true) connected = false
    }
    fun resize(columns: Int, lines: Int) {
        if (columns !in 2..500 || lines !in 1..500) return
        if (cols == columns && rows == lines) return
        cols = columns; rows = lines
        if (connected) socket?.send(terminalResize(cols, rows))
    }

    private suspend fun runConnection(environmentId: String, id: String) {
        val connection = api() ?: return
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            status = "Connecting terminal…"; connected = false
            val frames = Channel<ByteArray>(32)
            val opened = CompletableDeferred<Unit>()
            var permanentFailure = false
            val ws = connection.terminalClient().newWebSocket(connection.terminalRequest(environmentId, id), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { enqueue(bytes.toByteArray()) }
                override fun onMessage(webSocket: WebSocket, text: String) { enqueue(text.toByteArray()) }
                private fun enqueue(bytes: ByteArray) {
                    // Backpressure the socket reader until xterm has consumed bounded chunks.
                    try { runBlocking { var offset = 0
                        while (offset < bytes.size) {
                            val end = minOf(offset + 32 * 1024, bytes.size)
                            frames.send(bytes.copyOfRange(offset, end)); offset = end
                        } } }
                    catch (_: Exception) { }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    permanentFailure = response?.code in listOf(401, 403, 404, 410)
                    val error = IOException("Terminal connection interrupted")
                    opened.completeExceptionally(error); frames.close(error)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null); frames.close(IOException("Terminal closed"))
                }
            })
            socket = ws
            try {
                withTimeout(15_000) { opened.await() }
                output.clear(); renderer?.invoke(++sequence, output.snapshot(), true)
                connected = true; status = "Connected"; backoff = 1_000
                ws.send(terminalResize(cols, rows))
                for (bytes in frames) {
                    output.append(bytes)
                    val render = renderer
                    if (render != null) {
                        val seq = ++sequence
                        val ack = CompletableDeferred<Unit>()
                        acknowledgements[seq] = ack
                        try { render(seq, bytes, false); withTimeout(10_000) { ack.await() } }
                        finally { acknowledgements.remove(seq) }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                status = if (permanentFailure) "Terminal unavailable. Refresh the list or choose another." else "Terminal disconnected · retrying"
            } finally {
                frames.cancel(); ws.cancel()
                if (socket === ws) { socket = null; connected = false }
            }
            if (permanentFailure) return
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }
}
