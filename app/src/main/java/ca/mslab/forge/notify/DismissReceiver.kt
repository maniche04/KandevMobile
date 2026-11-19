package ca.mslab.forge.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Remembers a swiped-away notification so it isn't re-posted until its job changes state. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(AgentNotifications.EXTRA_TAG) ?: return
        AgentNotifications.onDismissed(tag, intent.getStringExtra(AgentNotifications.EXTRA_SIGNATURE).orEmpty())
        if (tag == "summary") AgentMonitorService.onSummaryDismissed()
    }
}
