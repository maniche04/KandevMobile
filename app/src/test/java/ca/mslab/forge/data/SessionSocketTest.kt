package ca.mslab.forge.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionSocketTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    @Before fun startServer() { server = MockWebServer(); server.start() }
    @After fun stopServer() { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }

    @Test fun `correlated acknowledgement and notification remain separate and token is a header`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                val id = request.getValue("id")
                webSocket.send("""{"type":"notification","action":"session.state_changed","payload":{"session_id":"s","new_state":"IDLE"}}""")
                webSocket.send("""{"id":$id,"type":"response","action":"message.add","payload":{"success":true}}""")
            }
        }))
        val socket = SessionSocket(server.url("/ws"), "test-token", client)
        try {
            val response = socket.request("message.add", buildJsonObject { put("content", "test") })
            assertEquals(true, response["success"]?.jsonPrimitive?.boolean)
            assertEquals("session.state_changed", withTimeout(5_000) { socket.events.receive() }.action)
            val request = server.takeRequest()
            assertEquals("/ws", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        } finally { socket.close() }
    }

    @Test fun `null payloads and malformed frames never drop the connection`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                val id = request.getValue("id")
                if (request["action"]?.jsonPrimitive?.content == "task.plan.get") {
                    // Kandev v0.98.0 answers "no plan" with a null payload.
                    webSocket.send("""{"id":$id,"type":"response","action":"task.plan.get","payload":null}""")
                } else {
                    webSocket.send("not json at all")
                    webSocket.send("""{"type":"notification","action":"session.todos_updated","payload":[1,2]}""")
                    webSocket.send("""{"id":$id,"type":"response","action":"ping","payload":{"ok":true}}""")
                }
            }
        }))
        val socket = SessionSocket(server.url("/ws"), "", client)
        try {
            assertTrue(socket.request("task.plan.get", buildJsonObject { put("task_id", "t") }).isEmpty())
            // The same connection keeps working after the null payload and garbage frames.
            assertEquals(true, socket.request("ping", JsonObject(emptyMap()))["ok"]?.jsonPrimitive?.boolean)
        } finally { socket.close() }
    }

    @Test fun `permission stale diagnostics expose only the allowlisted machine cause`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request=Json.parseToJsonElement(text).jsonObject
                webSocket.send("""{"id":${request["id"]},"type":"error","action":"permission.respond","payload":{"code":"internal_error","message":"Failed to respond to permission: permission_stale: private diagnostic"}}""")
            }
        }))
        val socket=SessionSocket(server.url("/ws"),"",client)
        try {
            val error=runCatching { socket.request("permission.respond",buildJsonObject{}) }.exceptionOrNull() as KandevApiException
            assertEquals("permission_stale",error.code);assertFalse(error.message!!.contains("private"))
        } finally {socket.close()}
    }

    @Test fun `disconnect fails in-flight sends and never replays them`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.close(1000, "test close") }
        }))
        val socket = SessionSocket(server.url("/ws"), "", client)
        try {
            val error = runCatching { socket.request("message.add", buildJsonObject { put("message_id", "stable-id") }) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(1, server.requestCount)
        } finally { socket.close() }
    }

    @Test fun `server error rejects a request without exposing server diagnostics`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = Json.parseToJsonElement(text).jsonObject.getValue("id")
                webSocket.send("""{"id":$id,"type":"error","action":"message.add","payload":{"message":"private diagnostic"}}""")
            }
        }))
        val socket = SessionSocket(server.url("/ws"), "", client)
        try {
            val error = runCatching { socket.request("message.add", buildJsonObject {}) }.exceptionOrNull()
            assertTrue(error is KandevApiException)
            assertFalse(error!!.message!!.contains("private diagnostic"))
        } finally { socket.close() }
    }
}
