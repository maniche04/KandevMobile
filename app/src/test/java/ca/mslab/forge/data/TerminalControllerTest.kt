package ca.mslab.forge.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class TerminalControllerTest {
    @Test fun `PTY reconnect keeps its identity resizes and never replays input`() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        val inputs = CopyOnWriteArrayList<String>()
        val peers = CopyOnWriteArrayList<WebSocket>()
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peers += webSocket }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    inputs += bytes.utf8()
                    if (bytes[0].toInt() != 1) webSocket.send("echoed".encodeUtf8())
                }
            }))
        }
        server.start()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val api = KandevApi(server.url("/").toString(), "private-token", client)
        val terminal = TerminalController(scope, { api })
        val output = StringBuilder()
        terminal.bindRenderer { seq, bytes, reset ->
            if (reset) output.clear()
            output.append(bytes.toString(Charsets.UTF_8)); terminal.drained(seq)
        }
        try {
            terminal.connect("environment", "ordinary-terminal")
            await { terminal.connected && inputs.size >= 1 }
            terminal.input("hello\n")
            await { output.contains("echoed") }
            peers.first().close(1001, "test interruption")
            await { peers.size == 2 && terminal.connected && inputs.size >= 3 }
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("/terminal/environment/environment?terminalId=ordinary-terminal", first.path)
            assertEquals(first.path, second.path)
            assertEquals("Bearer private-token", second.getHeader("Authorization"))
            assertEquals(1, inputs.count { it == "hello\n" })
            assertEquals(2, inputs.count { it.startsWith("\u0001") })
            terminal.pause(); terminal.input("must not queue\n")
            delay(100)
            assertFalse(inputs.contains("must not queue\n"))
        } finally {
            terminal.stop(); scope.cancel(); server.shutdown()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `missing PTY stops retrying and never creates a replacement shell`() = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(404)); server.start()
        val client = OkHttpClient()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val terminal = TerminalController(scope, { KandevApi(server.url("/").toString(), "", client) })
        try {
            terminal.connect("env", "gone")
            await { terminal.status.startsWith("Terminal unavailable") }
            delay(1200)
            assertFalse(terminal.connected); assertEquals(1, server.requestCount)
        } finally {
            terminal.stop(); scope.cancel(); server.shutdown()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(7000) { while (!condition()) delay(10) } }
}
