package ca.mslab.forge.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.mslab.forge.data.sync.ForgeHub
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Inline reply from a "turn finished" notification. One explicit send; never retried automatically. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifications.KEY_REPLY)?.toString()?.trim()
        val taskId = intent.getStringExtra(AgentNotifications.EXTRA_TASK)
        val sessionId = intent.getStringExtra(AgentNotifications.EXTRA_SESSION)
        if (text.isNullOrEmpty() || taskId == null || sessionId == null) return
        val pending = goAsync()
        val hub = ForgeHub.get(context)
        hub.scope.launch {
            val result = runCatching {
                val profile = hub.restore() ?: error("Not connected")
                withTimeout(20_000) {
                    profile.api.sessionSocket().use { socket ->
                        val id = UUID.randomUUID().toString()
                        socket.request("message.add", buildJsonObject {
                            put("task_id", taskId); put("session_id", sessionId); put("content", text)
                            put("message_id", id); put("client_message_id", id)
                        })
                    }
                }
            }
            AgentNotifications.replyResult(context, taskId, result.isSuccess, text)
            pending.finish()
        }
    }
}
