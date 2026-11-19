package ca.mslab.forge.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One delivered alert: when it arrived, how long push took, the priority it arrived with and what the app did.
 * [priority] reads "high", "normal" or "high→normal" (FCM lowered it).
 */
@Serializable
internal data class PushEntry(
    val at: Long, val source: String, val kind: String, val title: String = "",
    val delayMs: Long? = null, val priority: String? = null, val outcome: String,
)

/** Settings › Delivery log: the last [LIMIT] alerts, kept across restarts. */
internal object PushLog {
    private const val LIMIT = 60
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<PushEntry>>(emptyList())
    val entries: StateFlow<List<PushEntry>> = _entries
    @Volatile private var loaded = false

    fun load(context: Context): StateFlow<List<PushEntry>> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                _entries.value = runCatching {
                    json.decodeFromString(ListSerializer(PushEntry.serializer()), prefs(context).getString("entries", "[]")!!)
                }.getOrDefault(emptyList())
                loaded = true
            }
        }
        return entries
    }

    fun add(context: Context, entry: PushEntry) = synchronized(this) {
        load(context)
        val next = (listOf(entry) + _entries.value).take(LIMIT)
        _entries.value = next
        prefs(context).edit().putString("entries", json.encodeToString(ListSerializer(PushEntry.serializer()), next)).apply()
    }

    fun clear(context: Context) = synchronized(this) {
        _entries.value = emptyList()
        prefs(context).edit().remove("entries").apply()
    }

    fun priority(original: Int, delivered: Int): String? {
        fun name(value: Int) = when (value) { 1 -> "high"; 2 -> "normal"; else -> null }
        val from = name(original) ?: return name(delivered)
        val to = name(delivered) ?: return from
        return if (from == to) from else "$from→$to"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("forge-push-log", Context.MODE_PRIVATE)
}
