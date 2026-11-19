package ca.mslab.forge.push

import android.content.Context
import ca.mslab.forge.data.sync.ForgeHub
import ca.mslab.forge.data.sync.LinkState
import ca.mslab.forge.data.sync.toRow
import ca.mslab.forge.data.sync.toTask
import ca.mslab.forge.notify.AgentNotifications
import ca.mslab.forge.notify.AlertKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** How a push arrived, for the delivery log. */
internal data class Delivery(val source: String, val delayMs: Long?, val priority: String?) {
    fun log(context: Context, kind: String, outcome: String, title: String = "") =
        PushLog.add(context, PushEntry(System.currentTimeMillis(), source, kind, title.take(80), delayMs, priority, outcome))
}

/**
 * Shared rendering for decrypted FCM and legacy UnifiedPush messages. The push is the primary alert:
 * it is shown even while the live link is up, and the on-device monitor only fills in when no push came.
 */
internal object RelayAlerts {
    private val json = Json { ignoreUnknownKeys = true }
    fun receive(context: Context, body: String, delivery: Delivery) {
        val alert = runCatching { json.decodeFromString(RelayAlert.serializer(), body) }.getOrNull()
            ?: return delivery.log(context, "unknown", "dropped (unreadable alert)")
        val hub = ForgeHub.get(context)
        hub.scope.launch {
            // Keep the cached task list honest so the app opens with the latest known state, offline too.
            // While the live link is up it is the authority: a push can trail an auto-approval and would
            // otherwise pin a stale "needs approval" on the row.
            if (hub.link.state.value != LinkState.Live) runCatching {
                val dao = hub.restore()?.database?.dao() ?: return@runCatching
                val row = dao.task(alert.task_id) ?: return@runCatching
                val task = row.toTask() ?: return@runCatching
                dao.upsertTasks(listOf(task.copy(state = alert.state ?: task.state, sessionState = alert.session_state ?: task.sessionState,
                    sessionPendingAction = alert.pending_action, summary = task.summary?.copy(pendingAction = alert.pending_action,
                        running = alert.session_state in setOf("RUNNING", "STARTING"))).toRow(row.workspaceId)))
            }
        }
        if (alert.kind == "test") {
            AgentNotifications.post(context, AlertKind.TurnFinished, "test", null, null,
                "Offline alerts work", alert.body.ifBlank { "Forge will reach you even when Kandev is out of reach." }, null)
            return delivery.log(context, alert.kind, "shown", alert.title)
        }
        // The relay skips the task on screen; this covers a presence report that hadn't landed yet.
        if (hub.visibleTask.value == alert.task_id) return delivery.log(context, alert.kind, "not shown (task on screen)", alert.title)
        val kind = when (alert.kind) { "needs_you" -> AlertKind.NeedsYou; "failed" -> AlertKind.Failed; else -> AlertKind.TurnFinished }
        val at = runCatching { java.time.Instant.parse(alert.at).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        val outcome = AgentNotifications.fromPush(context, kind, alert.task_id, alert.session_id, alert.workspace_id,
            alert.title.ifBlank { "Agent update" }, alert.body, alert.agent,
            profileId = alert.agent_profile_id, model = alert.model, agentType = alert.agent_type, timestamp = at)
        delivery.log(context, alert.kind, outcome, alert.title)
    }
}
