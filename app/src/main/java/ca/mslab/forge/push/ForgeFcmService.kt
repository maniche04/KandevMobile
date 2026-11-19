package ca.mslab.forge.push

import ca.mslab.forge.data.sync.ForgeHub
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

class ForgeFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val relay = RelayClient.get(this)
        val delivery = Delivery("FCM", message.sentTime.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) },
            PushLog.priority(message.originalPriority, message.priority))
        if (!relay.usesFcm) return delivery.log(this, "unknown", "ignored (not paired over FCM)")
        val body = runCatching { FcmCipher.decrypt(relay.encryptionKey, message.data) }.getOrNull()
            ?: return delivery.log(this, "unknown", "dropped (could not decrypt; pair again)")
        RelayAlerts.receive(this, body, delivery)
    }

    override fun onNewToken(token: String) {
        val relay = RelayClient.get(this)
        relay.saveFcmToken(token)
        ForgeHub.get(this).scope.launch { relay.retryRegistration() }
    }
}
