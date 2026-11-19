package ca.mslab.forge.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Why the last fetch didn't land; the strip keeps the last good report and only hints at this. */
internal enum class UsageProblem { NotInstalled, Denied, Server, Offline, Invalid }

/**
 * Provider quotas for the home strip. Foreground-only: a fetch happens when home is shown and the last
 * attempt is older than [interval]; there is no background polling. The last good report is cached per
 * server profile so it paints instantly and offline.
 */
internal class UsageController(
    private val scope: CoroutineScope,
    private val api: () -> KandevApi?,
    private val cache: () -> SnapshotStore?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interval: Long = 10 * 60_000L,
    private val manualInterval: Long = 60_000L,
) {
    @Serializable private data class Saved(val body: String, val receivedAt: Long)

    var report by mutableStateOf<UsageReport?>(null); private set
    /** When this phone last received a good report (epoch millis). */
    var receivedAt by mutableStateOf<Long?>(null); private set
    var problem by mutableStateOf<UsageProblem?>(null); private set
    var loading by mutableStateOf(false); private set

    private var attemptedAt = Long.MIN_VALUE / 2
    private var manualAt = Long.MIN_VALUE / 2
    private var job: Job? = null
    internal val pending: Job? get() = job
    private var generation = 0
    private var identity: String? = null

    /** A different server profile: forget the old report and paint this one's cache. Same profile: no-op. */
    fun bind(profile: String) {
        if (profile == identity) return
        identity = profile
        generation++; job?.cancel(); loading = false
        report = null; receivedAt = null; problem = null
        attemptedAt = Long.MIN_VALUE / 2; manualAt = Long.MIN_VALUE / 2
        val current = generation
        job = scope.launch {
            val saved = runCatching { cache()?.read(KEY, Saved.serializer())?.value }.getOrNull()
            if (current == generation && report == null && saved != null)
                runCatching { parseUsageReport(saved.body) }.getOrNull()?.let { report = it; receivedAt = saved.receivedAt }
            if (current == generation) fetch(refresh = false, current)
        }
    }

    /** Disconnected: nothing of the old server's quotas stays on screen. */
    fun clear() {
        identity = null; generation++; job?.cancel(); loading = false
        report = null; receivedAt = null; problem = null
    }

    /** Home became visible: revalidate if the last attempt is older than the interval. */
    fun refreshIfDue() {
        if (job?.isActive == true || clock() - attemptedAt < interval) return
        val current = generation
        job = scope.launch { fetch(refresh = false, current) }
    }

    /** Explicit Refresh: asks the plugin to re-read providers, at most once a minute. */
    fun refreshNow() {
        if (job?.isActive == true) return
        val forced = clock() - manualAt >= manualInterval
        if (forced) manualAt = clock()
        val current = generation
        job = scope.launch { fetch(refresh = forced, current) }
    }

    private suspend fun fetch(refresh: Boolean, current: Int) {
        val connection = api() ?: return
        attemptedAt = clock(); loading = true
        try {
            val body = connection.providerUsage(refresh)
            val parsed = parseUsageReport(body)
            if (current != generation) return
            report = parsed; receivedAt = clock(); problem = null
            runCatching { cache()?.write(KEY, Saved.serializer(), Saved(body, clock())) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (current != generation) return
            problem = when {
                e is UsageUnavailable && e.status == 404 -> UsageProblem.NotInstalled
                e is UsageUnavailable && e.status in setOf(401, 403) -> UsageProblem.Denied
                e is UsageUnavailable -> UsageProblem.Server
                e is java.io.IOException -> UsageProblem.Offline
                else -> UsageProblem.Invalid
            }
            // A removed plugin must not leave a frozen report behind.
            if (problem == UsageProblem.NotInstalled) { report = null; receivedAt = null; runCatching { cache()?.remove(KEY) } }
        } finally { if (current == generation) loading = false }
    }

    companion object { private const val KEY = "provider-usage" }
}
