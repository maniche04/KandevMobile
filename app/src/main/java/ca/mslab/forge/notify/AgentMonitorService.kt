package ca.mslab.forge.notify

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ca.mslab.forge.data.db.RuntimeRow
import ca.mslab.forge.data.db.TaskRow
import ca.mslab.forge.data.sync.ForgeHub

/**
 * Keeps the live link open while agents are working, so progress and alerts arrive with the app in
 * the background. It stops itself once nothing has been running for a short grace period.
 */
class AgentMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = AgentNotifications.summary(this, lastActive, lastRuntimes)
        if (Build.VERSION.SDK_INT >= 34) startForeground(AgentNotifications.SUMMARY_NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(AgentNotifications.SUMMARY_NOTIFICATION_ID, notification)
        ForgeHub.get(this).setMonitoring(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ForgeHub.get(this).setMonitoring(false)
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false
        @Volatile private var lastActive: List<TaskRow> = emptyList()
        @Volatile private var lastRuntimes: Map<String, RuntimeRow> = emptyMap()
        @Volatile private var lastKey = ""
        @Volatile private var dismissedKey: String? = null

        /** Starts the monitor if allowed; Android only permits this while the app is visible. */
        fun ensure(context: Context) {
            if (running || !AgentNotifications.canPost(context)) return
            try {
                context.startForegroundService(Intent(context, AgentMonitorService::class.java))
                running = true
            } catch (_: Exception) { /* Background start not allowed; offline push covers this case. */ }
        }

        /** Refreshes the summary when the set of jobs or their states change (not on every step). */
        fun update(context: Context, active: List<TaskRow>, runtimes: Map<String, RuntimeRow>) {
            lastActive = active; lastRuntimes = runtimes
            if (!running || active.isEmpty()) return
            val key = AgentNotifications.summaryKey(active)
            // Swiped away (Android 14+ allows it): stay quiet until a job starts, ends or needs you.
            if (key == lastKey || key == dismissedKey) return
            lastKey = key; dismissedKey = null
            context.getSystemService(NotificationManager::class.java)
                .notify(AgentNotifications.SUMMARY_NOTIFICATION_ID, AgentNotifications.summary(context, active, runtimes))
        }

        fun onSummaryDismissed() { dismissedKey = lastKey }

        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, AgentMonitorService::class.java))
            running = false
        }
    }
}
