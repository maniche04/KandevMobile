package ca.mslab.forge

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ca.mslab.forge.data.sync.ForgeHub
import ca.mslab.forge.notify.AgentNotifications
import ca.mslab.forge.push.RelayClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val hub = ForgeHub.get(this)
        AgentNotifications.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                hub.setForeground(true)
                hub.scope.launch {
                    val relay = RelayClient.get(this@ForgeApplication)
                    relay.retryRegistration()
                    relay.verify()
                }
            }
            override fun onStop(owner: LifecycleOwner) = hub.setForeground(false)
        })
        AgentNotifications.start(this, hub)
        // The relay skips alerts for the task on screen; the report expires after 90 s if a clear is lost.
        hub.scope.launch {
            val relay = RelayClient.get(this@ForgeApplication)
            hub.visibleTask.collectLatest { task ->
                relay.reportPresence(task)
                while (task != null) { delay(60_000); relay.reportPresence(task) }
            }
        }
    }
}
