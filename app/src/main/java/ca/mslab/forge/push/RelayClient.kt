package ca.mslab.forge.push

import android.content.Context
import ca.mslab.forge.data.KandevApi
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** State of offline alerts, shown in Settings. */
data class PushStatus(
    val relayUrl: String = "", val hasKey: Boolean = false, val distributor: String? = null,
    val usesFcm: Boolean = false, val endpoint: Boolean = false, val registered: Boolean = false, val message: String? = null,
    /** When the relay last confirmed it still has this phone (0 = never). */
    val confirmedAt: Long = 0,
)

/**
 * Pairs this device with forge-relay, a small service next to Kandev that forwards agent alerts as
 * encrypted push through FCM or an existing UnifiedPush registration. Alerts then arrive even when Kandev
 * itself is unreachable (Tailscale off, away from home).
 */
internal class RelayClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("forge-push", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val _status = MutableStateFlow(load())
    val status: StateFlow<PushStatus> = _status

    private val registration = Mutex()
    val usesFcm: Boolean get() = prefs.getString("transport", null) == "fcm"
    val fcmAvailable: Boolean get() = FirebaseApp.getApps(context).isNotEmpty() &&
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    val encryptionKey: String get() = synchronized(this) {
        prefs.getString("encryption_key", null) ?: Base64.getEncoder().encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        ).also { prefs.edit().putString("encryption_key", it).commit() }
    }
    @Serializable private data class FcmDevice(val id: String, val token: String, val encryption_key: String,
        val name: String, val transport: String = "fcm")

    suspend fun pairFcm(url: String, key: String) {
        check(fcmAvailable) { "FCM needs Google Play services and a Firebase-configured build." }
        configure(url, key.ifBlank { prefs.getString("key", "").orEmpty() })
        prefs.edit().putString("transport", "fcm").apply()
        saveFcmToken(currentFcmToken())
        register().getOrThrow()
        org.unifiedpush.android.connector.UnifiedPush.unregister(context, "default")
    }

    private suspend fun currentFcmToken(): String {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        return suspendCancellableCoroutine { continuation ->
            messaging.token.addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM registration failed"))
                }
            }
        }
    }

    /**
     * Asks the relay whether it still has this phone. The relay drops a phone whose FCM token died, and
     * nothing told the app before, so Settings kept saying "Paired". Re-pairs with the current token.
     * Unreachable relay (away from home): status is kept as it is.
     */
    suspend fun verify() = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean("registered", false) || relayUrl.isBlank()) return@withContext
        val (code, body) = runCatching {
            KandevApi.validateServerUrl(relayUrl)
            client.newCall(Request.Builder().url("$relayUrl/v1/devices/$deviceId")
                .header("Authorization", "Bearer ${prefs.getString("key", "")}").get().build()).execute()
                .use { it.code to it.body?.string().orEmpty() }
        }.getOrNull() ?: return@withContext
        when {
            code in 200..299 -> { prefs.edit().putLong("confirmed", System.currentTimeMillis()).apply(); refresh() }
            // Relays before this check answer "not found" for the route itself: nothing to learn from them.
            code == 404 && "unknown device" in body -> {
                prefs.edit().putBoolean("registered", false).putLong("confirmed", 0)
                    .putString("error", "The relay had dropped this phone. Pairing again…").apply()
                refresh()
                if (usesFcm && fcmAvailable) runCatching { saveFcmToken(currentFcmToken()) }
                register().onSuccess { setMessage("The relay had dropped this phone; it was paired again.") }
            }
            code == 401 -> setMessage("The relay rejected the pairing key. Pair this phone again.")
        }
    }

    /** Tells the relay which task is on screen, so it doesn't push alerts this phone would not show. */
    suspend fun reportPresence(taskId: String?) = withContext(Dispatchers.IO) {
        if (!prefs.getBoolean("registered", false) || relayUrl.isBlank()) return@withContext
        runCatching {
            KandevApi.validateServerUrl(relayUrl)
            val body = buildJsonObject { put("task_id", JsonPrimitive(taskId)); put("ttl", JsonPrimitive(90)) }.toString()
            client.newCall(Request.Builder().url("$relayUrl/v1/devices/$deviceId/presence")
                .header("Authorization", "Bearer ${prefs.getString("key", "")}")
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().close()
        }
    }

    fun saveFcmToken(token: String) {
        if (prefs.getString("fcm_token", null) != token) {
            prefs.edit().putString("fcm_token", token).apply {
                if (usesFcm) putBoolean("registered", false)
            }.apply()
            refresh()
        }
    }

    suspend fun retryRegistration() {
        if (usesFcm && !prefs.getBoolean("registered", false) && status.value.hasKey &&
            !prefs.getString("fcm_token", null).isNullOrBlank()) register()
    }

    @Serializable private data class Info(val vapid_public_key: String, val version: Int = 1)
    @Serializable private data class Device(val id: String, val endpoint: String, val p256dh: String, val auth: String, val name: String)

    val deviceId: String get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    val relayUrl: String get() = prefs.getString("relay", "").orEmpty()
    val vapidKey: String? get() = prefs.getString("vapid", null)

    /** forge-relay listens next to Kandev on port 38430 by default. */
    fun defaultRelayUrl(serverUrl: String): String = serverUrl.toHttpUrlOrNull()?.newBuilder()?.port(38430)?.build()
        ?.toString()?.removeSuffix("/").orEmpty()

    fun configure(url: String, key: String) {
        prefs.edit().putString("relay", url.trim().removeSuffix("/")).putString("key", key.trim()).putBoolean("registered", false).apply()
        refresh()
    }

    /** Fetches the relay's VAPID key, which UnifiedPush needs before registering. */
    suspend fun fetchVapidKey(): String = withContext(Dispatchers.IO) {
        val url = requireNotNull(relayUrl.takeIf { it.isNotBlank() }) { "Set the relay address first." }
        KandevApi.validateServerUrl(url)
        val body = client.newCall(Request.Builder().url("$url/v1/info").build()).execute().use {
            check(it.isSuccessful) { "Relay returned HTTP ${it.code}" }; it.body!!.string()
        }
        json.decodeFromString(Info.serializer(), body).vapid_public_key.also { prefs.edit().putString("vapid", it).apply() }
    }

    fun saveEndpoint(endpoint: String, p256dh: String, auth: String) {
        prefs.edit().putString("endpoint", endpoint).putString("p256dh", p256dh).putString("auth", auth)
            .putBoolean("registered", false).apply()
        refresh()
    }

    fun clearEndpoint() {
        prefs.edit().remove("endpoint").remove("p256dh").remove("auth").apply {
            if (!usesFcm) putBoolean("registered", false)
        }.apply()
        refresh()
    }

    /** Sends the push endpoint to the relay. Needs Kandev's network (home or Tailscale). */
    suspend fun register(): Result<Unit> = withContext(Dispatchers.IO) {
        registration.withLock { runCatching {
            KandevApi.validateServerUrl(relayUrl)
            val token = prefs.getString("fcm_token", null)
            val body = if (usesFcm) json.encodeToString(FcmDevice.serializer(),
                FcmDevice(deviceId, requireNotNull(token) { "No FCM token yet." }, encryptionKey, android.os.Build.MODEL))
            else json.encodeToString(Device.serializer(), Device(deviceId,
                requireNotNull(prefs.getString("endpoint", null)) { "No push endpoint yet." },
                prefs.getString("p256dh", "")!!, prefs.getString("auth", "")!!, android.os.Build.MODEL))
            val request = Request.Builder().url("$relayUrl/v1/devices")
                .header("Authorization", "Bearer ${prefs.getString("key", "")}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use {
                check(it.code != 401) { "The relay rejected the pairing key." }
                check(it.isSuccessful) { "Relay returned HTTP ${it.code}" }
            }
            val current = !usesFcm || token == prefs.getString("fcm_token", null)
            prefs.edit().putBoolean("registered", current).putLong("confirmed", if (current) System.currentTimeMillis() else 0)
                .remove("error").apply()
        }.onFailure { prefs.edit().putString("error", it.message ?: "Registration failed").apply() }.also { refresh() } }
    }

    suspend fun sendTest(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$relayUrl/v1/devices/$deviceId/test")
                .header("Authorization", "Bearer ${prefs.getString("key", "")}").post(ByteArray(0).toRequestBody()).build())
                .execute().use { check(it.isSuccessful) { "Relay returned HTTP ${it.code}" } }
        }
    }

    suspend fun unregister() = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$relayUrl/v1/devices/$deviceId")
                .header("Authorization", "Bearer ${prefs.getString("key", "")}").delete().build())
                .execute().use { check(it.isSuccessful) { "Relay returned HTTP ${it.code}" } }
        }.getOrThrow()
        if (usesFcm && fcmAvailable) FirebaseMessaging.getInstance().isAutoInitEnabled = false
        prefs.edit().clear().apply(); refresh()
    }

    fun setMessage(text: String?) { prefs.edit().putString("error", text).apply(); refresh() }

    fun refresh() { _status.value = load() }

    private fun load() = PushStatus(
        relayUrl = prefs.getString("relay", "").orEmpty(), hasKey = !prefs.getString("key", null).isNullOrBlank(),
        usesFcm = usesFcm,
        distributor = if (usesFcm) "FCM" else runCatching { org.unifiedpush.android.connector.UnifiedPush.getAckDistributor(context) }.getOrNull(),
        endpoint = prefs.getString("endpoint", null) != null, registered = prefs.getBoolean("registered", false),
        message = prefs.getString("error", null), confirmedAt = prefs.getLong("confirmed", 0),
    )

    companion object {
        @Volatile private var instance: RelayClient? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: RelayClient(context.applicationContext).also { instance = it } }
    }
}
