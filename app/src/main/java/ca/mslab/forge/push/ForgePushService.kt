package ca.mslab.forge.push

import ca.mslab.forge.data.sync.ForgeHub
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Alert forwarded by forge-relay. Kept small: Web Push payloads are limited to about 4 KB. */
@Serializable
internal data class RelayAlert(
    val v: Int = 1, val kind: String, val task_id: String = "", val session_id: String? = null,
    val workspace_id: String? = null, val title: String = "", val body: String = "", val agent: String? = null,
    val model: String? = null, val agent_type: String? = null, val agent_profile_id: String? = null,
    val state: String? = null, val session_state: String? = null, val pending_action: String? = null, val at: String? = null,
)

/** Receives decrypted UnifiedPush messages, even while Kandev itself is out of reach. */
class ForgePushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (RelayClient.get(this).usesFcm) return
        val keys = endpoint.pubKeySet ?: return RelayClient.get(this).setMessage("The distributor did not provide encryption keys.")
        val relay = RelayClient.get(this)
        relay.saveEndpoint(endpoint.url, keys.pubKey, keys.auth)
        ForgeHub.get(this).scope.launch { relay.register() }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val delivery = Delivery("UnifiedPush", null, null)
        if (RelayClient.get(this).usesFcm) return delivery.log(this, "unknown", "ignored (paired over FCM)")
        if (!message.decrypted) return delivery.log(this, "unknown", "dropped (could not decrypt; pair again)")
        RelayAlerts.receive(this, message.content.decodeToString(), delivery)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (RelayClient.get(this).usesFcm) return
        RelayClient.get(this).setMessage(when (reason) {
            FailedReason.NETWORK -> "The push distributor is offline. Try again."
            FailedReason.ACTION_REQUIRED -> "Open your push distributor app (ntfy) to finish setup."
            FailedReason.VAPID_REQUIRED -> "The relay key is missing. Re-enable offline alerts."
            else -> "Push registration failed."
        })
    }

    override fun onUnregistered(instance: String) { RelayClient.get(this).clearEndpoint() }
}
