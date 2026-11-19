package ca.mslab.forge.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import ca.mslab.forge.MainActivity
import ca.mslab.forge.R
import ca.mslab.forge.data.ChatMessage
import ca.mslab.forge.data.TaskSummary
import ca.mslab.forge.data.db.RuntimeRow
import ca.mslab.forge.data.db.TaskRow
import ca.mslab.forge.data.isOpenPermission
import ca.mslab.forge.data.sync.ForgeHub
import ca.mslab.forge.data.sync.LinkState
import ca.mslab.forge.data.sync.TodoItem
import ca.mslab.forge.data.sync.storeJson
import ca.mslab.forge.data.sync.toMessage
import ca.mslab.forge.data.sync.toTask
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.builtins.ListSerializer

/** What a notification is about; shared by the on-device monitor and the offline push path. */
internal enum class AlertKind { NeedsYou, TurnFinished, Failed }

/**
 * Turns agent activity into notifications:
 * - one live, silent notification per running agent (current step, todos, elapsed time;
 *   Android 16 Live Updates), bundled under a summary that lists every job,
 * - a heads-up when an agent needs you (always opens the request in the app),
 * - a conversation-style "your turn" message with inline Reply when an agent finishes.
 * Nothing is posted for the task on screen, and a swiped-away live notification stays away
 * until that job's state changes.
 *
 * Alerts come from forge-relay's push when this phone is paired; the live link's own alert is a fallback
 * posted only if no push arrived within [PUSH_GRACE_MS]. A push that follows a fallback updates it quietly.
 */
internal object AgentNotifications {
    const val CHANNEL_LIVE = "agents_live"
    const val CHANNEL_ATTENTION = "needs_you"
    /** HIGH importance, so a finished turn pops up. Replaces "turns", which was created at DEFAULT. */
    const val CHANNEL_TURNS = "turn_alerts"
    private const val LEGACY_CHANNEL_TURNS = "turns"
    private const val PUSH_GRACE_MS = 15_000L
    const val EXTRA_TASK = "ca.mslab.forge.TASK"
    const val EXTRA_SESSION = "ca.mslab.forge.SESSION"
    const val EXTRA_WORKSPACE = "ca.mslab.forge.WORKSPACE"
    const val EXTRA_REVIEW = "ca.mslab.forge.REVIEW"
    const val EXTRA_TAG = "ca.mslab.forge.TAG"
    const val EXTRA_SIGNATURE = "ca.mslab.forge.SIGNATURE"
    const val KEY_REPLY = "reply"
    private const val GROUP_LIVE = "forge.live"
    private const val SUMMARY_ID = 1
    const val SUMMARY_NOTIFICATION_ID = SUMMARY_ID

    private val ACCENT = Color.rgb(0xA6, 0x4B, 0x32)
    private val RUNNING = Color.rgb(0x2F, 0x64, 0xA8)
    private val ATTENTION = Color.rgb(0xB7, 0x79, 0x1F)
    private val SUCCESS = Color.rgb(0x2F, 0x7A, 0x4F)
    private val FAILURE = Color.rgb(0xC8, 0x39, 0x4D)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_LIVE, "Running agents", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live progress while agents work"; setShowBadge(false)
            },
            NotificationChannel(CHANNEL_ATTENTION, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent is waiting for an approval or an answer"; enableVibration(true)
            },
            NotificationChannel(CHANNEL_TURNS, "Turn finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent finished its turn or stopped with an error"; enableVibration(true)
            },
        ))
        // A channel keeps the importance it was created with, so the old DEFAULT one is replaced, not changed.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_TURNS)
    }

    fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private data class Seen(val running: Boolean, val pending: String?, val state: String, val sessionState: String?)

    /** Live notifications the user swiped away, with the state they were dismissed in. */
    private val dismissed = ConcurrentHashMap<String, String>()
    /** When each task was first seen running in this turn, for the elapsed-time chronometer. */
    private val runningSince = ConcurrentHashMap<String, Long>()
    private val needsYou = ConcurrentHashMap<String, Boolean>()
    /** What each posted live notification shows, so unchanged ones are not re-posted. */
    private val livePosted = ConcurrentHashMap<String, String>()
    /** Last "your turn" message per task, so an inline reply can be appended to the thread. */
    private data class LastTurn(val title: String, val text: String, val agent: String?, val brand: AgentBrand,
        val session: String?, val workspace: String?)
    private val lastTurn = ConcurrentHashMap<String, LastTurn>()
    private const val NOTICE_TITLE = "forge.notice.title"
    private const val NOTICE_TEXT = "forge.notice.text"
    private const val NOTICE_AGENT = "forge.notice.agent"
    private const val NOTICE_BRAND = "forge.notice.brand"
    @Volatile private var lastSummary = ""
    /** When a push last arrived per task and alert, so the local fallback doesn't repeat it. */
    private val pushedAt = ConcurrentHashMap<String, Long>()
    /** Event time of alerts the local fallback showed, so the late push for the same event only updates them. */
    private val fallbackAt = ConcurrentHashMap<String, Long>()
    /** A finished and a failed turn are one event: the relay and the link may classify it differently. */
    private fun eventKey(taskId: String, kind: AlertKind) = "$taskId:${if (kind == AlertKind.NeedsYou) "needs" else "turn"}"

    fun onDismissed(tag: String, signature: String) { dismissed[tag] = signature; if (tag == "summary") lastSummary = signature }

    /** Watches the database while the link is live and keeps the foreground monitor alive while agents work. */
    fun start(context: Context, hub: ForgeHub) {
        val app = context.applicationContext
        // A new process has no live link yet: progress notifications left by a previous one are stale.
        app.getSystemService(NotificationManager::class.java).let { manager ->
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL_LIVE && it.tag?.startsWith("live:") == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
        hub.scope.launch {
            hub.profile.collectLatest { profile ->
                // Null is normal during cold-start profile restoration; keep delivered push alerts.
                if (profile == null) return@collectLatest
                val dao = profile.database.dao()
                var previous: Map<String, Seen>? = null
                var idleSince = 0L
                combine(dao.activeTasks(), dao.runtimes(), hub.link.state) { active, runtimes, link -> Triple(active, runtimes, link) }
                    .conflate()
                    .collect { (active, runtimes, link) ->
                        val running = active.filter { it.running }
                        running.forEach { runningSince.putIfAbsent(it.id, System.currentTimeMillis()) }
                        runningSince.keys.retainAll(running.map { it.id }.toSet())
                        val wantMonitor = active.isNotEmpty()
                        if (wantMonitor) { idleSince = 0; AgentMonitorService.ensure(app) }
                        else if (idleSince == 0L) idleSince = System.currentTimeMillis()
                        // Live progress is only shown while it is actually live; cached rows can be stale.
                        val byRuntime = runtimes.associateBy { it.sessionId }
                        val posted = renderLive(app, if (link == LinkState.Live) running else emptyList(), byRuntime, hub)
                        AgentMonitorService.update(app, active, byRuntime)
                        // Transitions are only trusted while continuously connected; reconnect catch-up
                        // is covered by the offline push relay instead of a burst of stale alerts.
                        if (link != LinkState.Live) { previous = null; return@collect }
                        val now = active.associate { it.id to Seen(it.running, it.pendingAction, it.state, it.sessionState) }
                        val before = previous
                        previous = now
                        if (before == null) return@collect
                        for (row in active) {
                            val old = before[row.id]
                            if (row.pendingAction != null && old?.pending == null) confirmNeedsYou(app, hub, row.id)
                        }
                        // A resolved request (here or on another client) retires its alert.
                        for ((id, old) in before) if (old.pending != null && now[id]?.pending == null) needsYou.remove(id)?.let {
                            app.getSystemService(NotificationManager::class.java).cancel("alert:$id", 0)
                        }
                        for ((id, old) in before) if (old.running && now[id]?.running != true) dismissed.remove("live:$id")
                        if (!wantMonitor && System.currentTimeMillis() - idleSince > 90_000) AgentMonitorService.stop(app)
                        // At most one redraw per second while agents stream; conflate() keeps only the latest
                        // state, so the final update is never lost (Android drops posts above ~5/s).
                        if (posted) delay(1_000)
                    }
            }
        }
        // Turn ends come straight from session events, so short turns (a few seconds) aren't missed.
        hub.scope.launch {
            hub.profile.collectLatest { profile ->
                profile ?: return@collectLatest
                profile.store.turns.collect { turn ->
                    launch {
                        val eventAt = System.currentTimeMillis()
                        delay(1_500) // let the reply and task refetch land first
                        val dao = profile.database.dao()
                        if (dao.task(turn.taskId)?.let { it.pendingAction != null || it.running } != false) return@launch
                        if (pushArrived(app, turn.taskId, AlertKind.TurnFinished, eventAt)) return@launch
                        val row = dao.task(turn.taskId) ?: return@launch
                        if (row.pendingAction != null || row.running) return@launch
                        val failed = turn.state == "FAILED" || row.sessionState == "FAILED" || row.state == "FAILED"
                        alert(app, hub, row, if (failed) AlertKind.Failed else AlertKind.TurnFinished, dao.runtimes().first(),
                            eventAt, turn.sessionId)
                    }
                }
            }
        }
        // Idle grace period: re-evaluate periodically so the monitor stops even without new events.
        hub.scope.launch {
            while (isActive) {
                delay(60_000)
                val profile = hub.profile.value ?: continue
                if (profile.database.dao().activeTasks().first().isEmpty()) AgentMonitorService.stop(app)
            }
        }
    }

    /** Auto-approved permissions resolve within a second; only alert if the request is still open. */
    private fun confirmNeedsYou(context: Context, hub: ForgeHub, taskId: String) {
        hub.scope.launch {
            val eventAt = System.currentTimeMillis()
            delay(4_000)
            val dao = hub.profile.value?.database?.dao() ?: return@launch
            if (dao.task(taskId)?.pendingAction == null || hub.link.state.value != LinkState.Live) return@launch
            if (pushArrived(context, taskId, AlertKind.NeedsYou, eventAt)) return@launch
            val row = dao.task(taskId) ?: return@launch
            if (row.pendingAction == null) return@launch
            alert(context, hub, row, AlertKind.NeedsYou, dao.runtimes().first(), eventAt)
        }
    }

    /**
     * Waits until [PUSH_GRACE_MS] after the event when this phone is paired with the relay, and reports
     * whether the push for it arrived meanwhile. Unpaired phones get the local alert at once.
     */
    private suspend fun pushArrived(context: Context, taskId: String, kind: AlertKind, eventAt: Long): Boolean {
        if (!ca.mslab.forge.push.RelayClient.get(context).status.value.registered) return false
        delay((eventAt + PUSH_GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0))
        // Slack for a push that beat the link's own event.
        return (pushedAt[eventKey(taskId, kind)] ?: 0L) >= eventAt - 10_000
    }

    /**
     * Shows a relay push. Returns what happened, for the delivery log. If the local fallback already showed
     * this event, the push refreshes it without a second buzz (or leaves it dismissed).
     */
    fun fromPush(context: Context, kind: AlertKind, taskId: String, sessionId: String?, workspaceId: String?, title: String,
        body: String, agent: String?, profileId: String?, model: String?, agentType: String?, timestamp: Long): String {
        val key = eventKey(taskId, kind)
        pushedAt[key] = System.currentTimeMillis()
        if (!canPost(context)) return "not shown (notifications are off)"
        val local = fallbackAt.remove(key)
        val sameEvent = local != null && kotlin.math.abs(local - timestamp) < 60_000
        if (sameEvent && !isShowing(context, taskId)) return "not shown (the app showed it first; dismissed)"
        post(context, kind, taskId, sessionId, workspaceId, title, body, agent, profileId, model, agentType, timestamp, quiet = sameEvent)
        return if (sameEvent) "updated quietly (the app showed it first)" else "shown"
    }

    /** Posts live notifications whose visible content changed; returns whether anything was posted. */
    private fun renderLive(context: Context, running: List<TaskRow>, runtimes: Map<String, RuntimeRow>, hub: ForgeHub): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val shown = manager.activeNotifications.filter { it.notification.channelId == CHANNEL_LIVE && it.id != SUMMARY_ID }.map { it.tag }.toSet()
        val keep = mutableSetOf<String>()
        var posted = false
        if (canPost(context)) for (row in running) {
            val task = row.toTask() ?: continue
            if (hub.visibleTask.value == task.id) continue
            val tag = "live:${task.id}"
            if (dismissed[tag] == "running") continue
            dismissed.remove(tag)
            val runtime = task.primarySessionId?.let { runtimes[it] }
            val since = runningSince[task.id] ?: System.currentTimeMillis()
            keep += tag
            val content = liveContent(task, runtime, since)
            if (tag in shown && livePosted[tag] == content) continue
            manager.notify(tag, 0, liveNotification(context, task, runtime, since))
            livePosted[tag] = content; posted = true
        }
        (shown - keep).forEach { manager.cancel(it, 0) }
        livePosted.keys.retainAll(keep)
        return posted
    }

    /** Everything [liveNotification] renders. */
    private fun liveContent(task: TaskSummary, runtime: RuntimeRow?, since: Long) = listOf(task.title, task.agentName,
        task.agentProfileId, task.primarySessionId, runtime?.activity, runtime?.todosJson, since).joinToString("\u0000")

    internal fun todos(runtime: RuntimeRow?): List<TodoItem> = runtime?.todosJson?.let {
        runCatching { storeJson.decodeFromString(ListSerializer(TodoItem.serializer()), it) }.getOrNull()
    }.orEmpty()

    /** The foreground notification: every active job in one glance, urgent ones first. */
    fun summary(context: Context, active: List<TaskRow>, runtimes: Map<String, RuntimeRow>): Notification {
        val tasks = active.mapNotNull { row -> row.toTask()?.let { row to it } }
            .sortedWith(compareByDescending<Pair<TaskRow, TaskSummary>> { it.first.pendingAction != null }.thenByDescending { it.first.lastActivity })
        val waiting = tasks.count { it.first.pendingAction != null }
        val working = tasks.count { it.first.running && it.first.pendingAction == null }
        val title = listOfNotNull(if (working > 0) "$working working" else null, if (waiting > 0) "$waiting need${if (waiting == 1) "s" else ""} you" else null)
            .joinToString(" · ").ifEmpty { "Watching your agents" }
        val inbox = Notification.InboxStyle().setBigContentTitle(title)
        tasks.take(6).forEach { (row, task) ->
            val runtime = task.primarySessionId?.let { runtimes[it] }
            val state = if (row.pendingAction != null) "Needs you" else runtime?.activity?.takeIf { it.isNotBlank() } ?: "Working"
            inbox.addLine("${if (row.pendingAction != null) "✋" else "▶"} ${task.title} — $state")
        }
        if (tasks.size > 6) inbox.setSummaryText("+${tasks.size - 6} more")
        return Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(if (waiting > 0) R.drawable.ic_stat_needs else R.drawable.ic_stat_running)
            .setColor(if (waiting > 0) ATTENTION else RUNNING)
            .setContentTitle(title)
            .setContentText(tasks.joinToString(" · ") { it.second.title }.ifEmpty { "Forge" })
            .setStyle(inbox)
            .setGroup(GROUP_LIVE).setGroupSummary(true).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(context, tasks.singleOrNull()?.second?.id, null, null, review = false))
            .setDeleteIntent(dismissIntent(context, "summary", title))
            .build()
    }

    fun summaryKey(active: List<TaskRow>) = active.joinToString("|") { "${it.id}:${it.running}:${it.pendingAction}" }

    private fun liveNotification(context: Context, task: TaskSummary, runtime: RuntimeRow?, since: Long): Notification {
        val todos = todos(runtime)
        val done = todos.count { it.status == "completed" }
        val current = todos.firstOrNull { it.status == "in_progress" }?.description
        val activity = runtime?.activity?.takeIf { it.isNotBlank() } ?: current ?: "Working…"
        val progressText = if (todos.isNotEmpty()) "$done/${todos.size}" else null
        val builder = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_running).setColor(RUNNING)
            .setContentTitle(task.title)
            .setContentText(activity)
            .setSubText(listOfNotNull(task.agentName, "Working", progressText).joinToString(" · "))
            .setLargeIcon(NotificationBranding.icon(context,
                NotificationBranding.resolve(context, task.agentProfileId), task.agentName))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setGroup(GROUP_LIVE).setSortKey("1-${task.title}")
            // Elapsed time for this turn, counted by the system without re-posting.
            .setWhen(since).setShowWhen(true).setUsesChronometer(true)
            .setContentIntent(openIntent(context, task.id, task.primarySessionId, null, review = false))
            .setDeleteIntent(dismissIntent(context, "live:${task.id}", "running"))
            .addAction(Notification.Action.Builder(null, "Open", openIntent(context, task.id, task.primarySessionId, null, review = false)).build())
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle().setStyledByProgress(true)
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_stat_running))
            if (todos.isEmpty()) style.setProgressIndeterminate(true)
            else {
                // One segment per todo: finished, current and upcoming steps read at a glance.
                style.setProgressSegments(todos.map { item ->
                    Notification.ProgressStyle.Segment(1).setColor(when (item.status) {
                        "completed" -> SUCCESS; "in_progress" -> RUNNING; "failed" -> FAILURE; else -> Color.LTGRAY
                    })
                })
                style.setProgress(done)
            }
            builder.setStyle(style)
            builder.setShortCriticalText(progressText ?: "Running")
            // Ask the system to promote this to a Live Update (status-bar chip, lock screen).
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        } else {
            if (todos.isNotEmpty()) builder.setProgress(todos.size, done, false)
            builder.setStyle(Notification.BigTextStyle().bigText((listOf(activity) + todoLines(todos)).distinct().joinToString("\n")))
        }
        return builder.build()
    }

    private suspend fun alert(context: Context, hub: ForgeHub, row: TaskRow, kind: AlertKind, runtimes: List<RuntimeRow>,
        eventAt: Long, session: String? = null) {
        val task = row.toTask() ?: return
        if (hub.visibleTask.value == task.id) return
        val sessionId = session ?: task.primarySessionId
        val runtime = runtimes.firstOrNull { it.sessionId == sessionId }
        val dao = hub.profile.value?.database?.dao()
        val recent = sessionId?.let { id -> dao?.messages(id)?.takeLast(40)?.mapNotNull { it.toMessage() } }.orEmpty()
        val body = when (kind) {
            AlertKind.NeedsYou -> requestText(row.pendingAction, recent.lastOrNull { it.isOpenPermission() || it.type == "clarification_request" })
            AlertKind.Failed -> task.summary?.activeError?.preview?.takeIf { it.isNotBlank() } ?: "The agent stopped with an error"
            AlertKind.TurnFinished -> recent.lastOrNull { it.author == "agent" && it.type in setOf("message", "content") }?.content
                ?: sessionId?.let { hub.profile.value?.store?.lastAgentReply(it) } ?: runtime?.activity ?: "Finished its turn"
        }
        post(context, kind, task.id, sessionId, row.workspaceId, task.title, body, task.agentName,
            profileId = task.agentProfileId)
        fallbackAt[eventKey(task.id, kind)] = eventAt
        if (ca.mslab.forge.push.RelayClient.get(context).status.value.registered)
            ca.mslab.forge.push.PushLog.add(context, ca.mslab.forge.push.PushEntry(System.currentTimeMillis(), "App",
                when (kind) { AlertKind.NeedsYou -> "needs_you"; AlertKind.Failed -> "failed"; else -> "turn_finished" },
                task.title.take(80), outcome = "shown by the app (no push within ${PUSH_GRACE_MS / 1000} s)"))
    }

    /** Live monitoring and push use the same layout, identity, actions and replacement key. */
    fun post(context: Context, kind: AlertKind, taskId: String, sessionId: String?, workspaceId: String?, title: String,
        body: String, agent: String?, profileId: String? = null, model: String? = null, agentType: String? = null,
        timestamp: Long = System.currentTimeMillis(), quiet: Boolean = false) {
        if (!canPost(context)) return
        if (kind == AlertKind.NeedsYou) needsYou[taskId] = true
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel("live:$taskId", 0)
        val text = plainText(body).take(1200)
        val brand = NotificationBranding.resolve(context, profileId, model, agentType)
        val open = openIntent(context, taskId, sessionId, workspaceId, review = kind == AlertKind.NeedsYou)
        val (icon, color, label) = when (kind) {
            AlertKind.NeedsYou -> Triple(R.drawable.ic_stat_needs, ATTENTION, "Needs you")
            AlertKind.Failed -> Triple(R.drawable.ic_stat_failed, FAILURE, "Failed")
            AlertKind.TurnFinished -> Triple(R.drawable.ic_stat_done, SUCCESS, "Your turn")
        }
        val turn = LastTurn(title, text, agent, brand, sessionId, workspaceId)
        if (kind == AlertKind.TurnFinished) lastTurn[taskId] = turn else lastTurn.remove(taskId)
        val builder = Notification.Builder(context, if (kind == AlertKind.NeedsYou) CHANNEL_ATTENTION else CHANNEL_TURNS)
            .setSmallIcon(icon).setColor(color)
            .setContentTitle(title).setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setSubText(label).setWhen(timestamp).setShowWhen(true)
            .setStyle(conversation(context, title, agent, brand, text, null, timestamp, label))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addExtras(noticeExtras(turn))
            .setCategory(when (kind) {
                AlertKind.NeedsYou -> Notification.CATEGORY_REMINDER
                AlertKind.Failed -> Notification.CATEGORY_ERROR
                AlertKind.TurnFinished -> Notification.CATEGORY_MESSAGE
            })
        if (Build.VERSION.SDK_INT < 28) builder.setLargeIcon(NotificationBranding.icon(context, brand, agent))
        if (kind == AlertKind.NeedsYou) {
            builder.addAction(Notification.Action.Builder(null, "Review", open).build())
        } else {
            if (kind == AlertKind.TurnFinished && sessionId != null) builder.addAction(replyAction(context, taskId, sessionId))
            builder.addAction(Notification.Action.Builder(null, "Open", open).build())
        }
        builder.setAutoCancel(true).setContentIntent(open).setOnlyAlertOnce(quiet)
        manager.notify("alert:$taskId", 0, builder.build())
    }

    private fun noticeExtras(turn: LastTurn) = Bundle().apply {
        putString(NOTICE_TITLE, turn.title); putString(NOTICE_TEXT, turn.text)
        putString(NOTICE_AGENT, turn.agent); putString(NOTICE_BRAND, turn.brand.name)
        putString(EXTRA_SESSION, turn.session); putString(EXTRA_WORKSPACE, turn.workspace)
    }

    /** A consistent model avatar and conversation layout, including approvals and failures. */
    private fun conversation(context: Context, title: String, agent: String?, brand: AgentBrand, text: String,
        reply: String?, timestamp: Long = System.currentTimeMillis(), label: String? = null): Notification.Style =
        if (Build.VERSION.SDK_INT >= 28) messaging(context, title, agent, brand, text, reply, timestamp, label)
        else Notification.BigTextStyle().setBigContentTitle(title)
            .bigText(listOfNotNull("${agent ?: "Agent"}: $text", reply?.let { "You: $it" }).joinToString("\n\n"))

    @androidx.annotation.RequiresApi(28)
    private fun messaging(context: Context, title: String, agent: String?, brand: AgentBrand, text: String,
        reply: String?, timestamp: Long, label: String?): Notification.MessagingStyle {
        val me = Person.Builder().setName("You").build()
        val name = agent?.takeIf { it.isNotBlank() } ?: "Agent"
        val agentPerson = Person.Builder().setName(listOfNotNull(name, label).joinToString(" · ")).setBot(true)
            .setKey("${brand.name}:${agent.orEmpty()}").setIcon(NotificationBranding.icon(context, brand, agent)).build()
        return Notification.MessagingStyle(me).setConversationTitle(title).setGroupConversation(false).apply {
            addMessage(text, timestamp, agentPerson)
            if (reply != null) addMessage(reply, System.currentTimeMillis(), me)
        }
    }

    private fun replyAction(context: Context, taskId: String, sessionId: String): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to the agent").build()
        val reply = PendingIntent.getBroadcast(context, ("reply:$taskId").hashCode(),
            Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_SESSION, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_done), "Reply", reply)
            .addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    fun isShowing(context: Context, taskId: String): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.tag == "alert:$taskId" }

    /** After an inline reply: show it in the thread (sent) or explain why it wasn't sent. */
    fun replyResult(context: Context, taskId: String, sent: Boolean, reply: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Notification extras survive a cold FCM process and process death before inline reply.
        val saved = manager.activeNotifications.firstOrNull { it.tag == "alert:$taskId" }?.notification?.extras
        val turn = lastTurn[taskId] ?: LastTurn(saved?.getString(NOTICE_TITLE) ?: "Agent update",
            saved?.getString(NOTICE_TEXT).orEmpty(), saved?.getString(NOTICE_AGENT),
            runCatching { AgentBrand.valueOf(saved?.getString(NOTICE_BRAND).orEmpty()) }.getOrDefault(AgentBrand.Unknown),
            saved?.getString(EXTRA_SESSION), saved?.getString(EXTRA_WORKSPACE))
        val open = openIntent(context, taskId, turn.session, turn.workspace, review = false)
        val builder = Notification.Builder(context, CHANNEL_TURNS)
            .setSmallIcon(if (sent) R.drawable.ic_stat_running else R.drawable.ic_stat_failed).setColor(if (sent) RUNNING else FAILURE)
            .setContentTitle(turn.title).setContentText(if (sent) reply else "Not sent: $reply")
            .setStyle(conversation(context, turn.title, turn.agent, turn.brand, turn.text, if (sent) reply else "Not sent: $reply",
                label = if (sent) "Working" else "Reply not sent"))
            .setSubText(if (sent) "Sent · the agent is working" else "Not sent · connect to Kandev to reply")
            .addExtras(noticeExtras(turn)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(if (sent) 15_000 else 0)
            .setContentIntent(open)
        manager.notify("alert:$taskId", 0, builder.build())
    }

    fun openIntent(context: Context, taskId: String?, sessionId: String?, workspaceId: String?, review: Boolean): PendingIntent =
        PendingIntent.getActivity(context, ("open:$taskId:$review").hashCode(),
            Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_WORKSPACE, workspaceId)
                .putExtra(EXTRA_REVIEW, review),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun dismissIntent(context: Context, tag: String, signature: String): PendingIntent =
        PendingIntent.getBroadcast(context, ("dismiss:$tag").hashCode(),
            Intent(context, DismissReceiver::class.java).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_SIGNATURE, signature),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
