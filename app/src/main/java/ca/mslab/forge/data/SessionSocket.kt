package ca.mslab.forge.data

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*

/** Server error codes the UI distinguishes: permissions, the follow-up queue and session recovery. */
internal val ALLOWED_ERROR_CODES = setOf(
    "permission_not_found", "permission_stale", "permission_already_resolved",
    "queue_full", "entry_not_found", "edit_conflict", "queue_conflict", "queue_changed", "queue_empty",
    "send_now_conflict", "turn_changed", "session_busy", "session_not_promptable",
    "branch_unrecoverable", "task_archived", "recovery_inspection_busy",
)

@Serializable
internal data class WireMessage(
    val id: String? = null, val type: String, val action: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/** Requests are correlated, bounded by a timeout, and never replayed after disconnection. */
internal class SessionSocket(url: HttpUrl, token: String, client: OkHttpClient) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val opened = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    val events = Channel<WireMessage>(256)
    private val socket = client.newWebSocket(
        Request.Builder().url(url).apply {
            if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
        }.build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // One malformed frame must never take down the connection (Kandev answers some requests,
                // such as task.plan.get for a task without a plan, with `"payload": null`).
                val message = try { parse(text) } catch (e: Exception) { return }
                try {
                    val response = message.id?.let { pending[it] }
                    if (message.type in setOf("response", "error") && response != null) {
                        if (message.type == "error" || message.payload["success"] == JsonPrimitive(false)) {
                            // Keep server diagnostics out of the product UI and logs: only known codes pass,
                            // and the UI maps each to its own wording.
                            val code = message.payload["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ALLOWED_ERROR_CODES }
                                ?: (message.payload["details"] as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it in ALLOWED_ERROR_CODES }
                                ?: message.payload["message"]?.jsonPrimitive?.contentOrNull
                                    ?.removePrefix("Failed to respond to permission: ")?.substringBefore(":")?.takeIf { it in ALLOWED_ERROR_CODES }
                            response.completeExceptionally(KandevApiException("Kandev rejected the request. Refresh the conversation.", code))
                        } else response.complete(message.payload)
                    } else if (message.type == "notification" && !events.trySend(message).isSuccess) {
                        fail(IOException("Conversation event buffer overflow"))
                        webSocket.cancel()
                    }
                } catch (e: Exception) {
                    fail(IOException("Invalid conversation response", e))
                    webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(IOException("Live connection interrupted", t))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                fail(IOException("Live connection closed"))
            }
        },
    )

    private fun parse(text: String): WireMessage {
        val frame = json.parseToJsonElement(text).jsonObject
        val payload = frame["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return WireMessage(frame["id"]?.jsonPrimitive?.contentOrNull, frame.getValue("type").jsonPrimitive.content,
            frame["action"]?.jsonPrimitive?.contentOrNull.orEmpty(), payload)
    }

    suspend fun request(action: String, payload: JsonObject, timeoutMs: Long = 30_000): JsonObject = withTimeout(timeoutMs) {
        opened.await()
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        try {
            if (!socket.send(json.encodeToString(WireMessage.serializer(), WireMessage(id, "request", action, payload)))) {
                throw IOException("Live connection is unavailable")
            }
            result.await()
        } finally { pending.remove(id) }
    }

    override fun close() {
        fail(IOException("Live connection closed"))
        socket.cancel()
    }

    private fun fail(error: IOException) {
        opened.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        events.close(error)
    }
}
