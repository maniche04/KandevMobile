package ca.mslab.forge.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

/**
 * Subscription quota telemetry from the Kandev Provider Usage plugin
 * (`GET /api/plugins/kandev-provider-usage/webhooks/providers`). The phone never talks to
 * Codex or Claude: Kandev reads the provider credentials on the server and this is read-only.
 */
internal data class UsageReport(
    val generatedAt: Instant?,
    val pollMinutes: Int?,
    val providers: List<ProviderQuota>,
    val unavailable: List<ProviderIssue>,
)

internal data class ProviderQuota(
    val id: String,
    val plan: String?,
    val account: String?,
    val windows: List<QuotaWindow>,
    val fetchedAt: Instant?,
    val source: String?,
) {
    val name get() = providerName(id)
    /** The windows a compact chip may show: the 5-hour and weekly ones, never scoped extras. */
    val headline get() = listOfNotNull(windows.firstOrNull { it.kind == WindowKind.FiveHour && !it.scoped },
        windows.firstOrNull { it.kind == WindowKind.Weekly && !it.scoped })
}

internal data class ProviderIssue(val id: String, val message: String) { val name get() = providerName(id) }

internal enum class WindowKind(val short: String, val title: String) {
    FiveHour("5h", "5-hour"), Weekly("Wk", "Weekly"), Other("", "")
}

internal data class QuotaWindow(
    val label: String,
    /** The reported duration. Null when the plugin didn't say; then the kind is never guessed. */
    val minutes: Long?,
    val usedPercent: Double,
    val resetAt: Instant?,
    val resetText: String?,
    val scoped: Boolean,
) {
    val kind: WindowKind get() = windowKind(minutes)
    val remainingPercent get() = (100.0 - usedPercent).coerceIn(0.0, 100.0)
    /** Chip label: "5h", "Wk", else the reported duration ("30d", "12h"). */
    val short get() = kind.short.ifEmpty {
        minutes?.let { if (it % 1440 == 0L) "${it / 1440}d" else if (it % 60 == 0L) "${it / 60}h" else null } ?: label.take(4)
    }
    val title get() = when {
        scoped || kind == WindowKind.Other -> label.ifBlank { minutes?.let(::durationTitle) ?: "Quota" }
        else -> kind.title
    }
}

/** 5-hour and weekly are recognized from the reported duration alone (with slack for rounding). */
internal fun windowKind(minutes: Long?): WindowKind = when (minutes) {
    null -> WindowKind.Other
    in 270L..330L -> WindowKind.FiveHour
    in 9_360L..10_800L -> WindowKind.Weekly
    else -> WindowKind.Other
}

private fun durationTitle(minutes: Long): String = when {
    minutes % 1440 == 0L -> "${minutes / 1440}-day"
    minutes % 60 == 0L -> "${minutes / 60}-hour"
    else -> "$minutes-minute"
}

/** Codex and Claude first, then the rest in a stable order. */
private val PRIORITY = listOf("codex", "claude")

internal fun providerName(id: String): String = when (id.lowercase()) {
    "codex" -> "Codex"; "claude" -> "Claude"; "gemini" -> "Gemini"; "antigravity" -> "Antigravity"
    "copilot" -> "Copilot"; "cursor" -> "Cursor"; "opencodego", "opencode" -> "OpenCode"; "augment" -> "Augment"
    "amp" -> "Amp"; "grok" -> "Grok"
    else -> id.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** The Kandev agent whose logo represents a provider; null keeps the neutral icon. */
internal fun providerLogoAgent(id: String): String? = when (id.lowercase()) {
    "codex" -> "codex-acp"; "claude" -> "claude-acp"; "opencodego", "opencode" -> "opencode-acp"
    "antigravity" -> "antigravity-acp"; "gemini" -> "gemini"
    else -> null
}

private val usageJson = Json { ignoreUnknownKeys = true }

/**
 * Tolerant decoding: the plugin's contract has changed between releases, so each field is read
 * by name with known aliases, and anything malformed is dropped rather than failing the strip.
 */
internal fun parseUsageReport(body: String): UsageReport {
    val root = usageJson.parseToJsonElement(body).jsonObject
    val providers = root.array("providers").mapNotNull { (it as? JsonObject)?.let(::parseProvider) }
        .sortedWith(compareBy<ProviderQuota> { PRIORITY.indexOf(it.id).let { i -> if (i < 0) PRIORITY.size else i } }.thenBy { it.id })
    val reported = providers.map { it.id }.toSet()
    val unavailable = root.array("unavailable").mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.string("provider") ?: return@mapNotNull null
        ProviderIssue(id, item.string("message") ?: "Usage unavailable").takeIf { id !in reported }
    }.distinctBy { it.id }
    return UsageReport(root.instant("generated_at"), root.number("poll_minutes")?.toInt(), providers, unavailable)
}

private fun parseProvider(item: JsonObject): ProviderQuota? {
    val id = (item.string("provider") ?: item.string("provider_id"))?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val account = (item["account"] as? JsonObject)?.let { it.string("label") ?: it.string("name") } ?: item.string("account")
    val windows = item.array("windows").mapNotNull { (it as? JsonObject)?.let(::parseWindow) }
    return ProviderQuota(id, item.string("plan") ?: item.string("plan_label"), account, windows,
        item.instant("fetched_at"), item.string("source"))
}

private fun parseWindow(item: JsonObject): QuotaWindow? {
    val used = item.number("utilization_pct") ?: item.number("utilization_percentage") ?: item.number("used_percent")
        ?: item.number("usedPercent") ?: item.number("remaining_percentage")?.let { 100 - it } ?: return null
    if (used.isNaN()) return null
    val minutes = item.number("window_seconds")?.let { (it / 60).toLong() }
        ?: (item.number("window_minutes") ?: item.number("windowMinutes") ?: item.number("windowDurationMins"))?.toLong()
    return QuotaWindow(item.string("label") ?: item.string("window_name") ?: "", minutes?.takeIf { it > 0 },
        used.coerceIn(0.0, 100.0), item.instant("reset_at") ?: item.instant("resetsAt"),
        item.string("reset_description"), item["scoped"]?.jsonPrimitive?.booleanOrNull == true)
}

private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
private fun JsonObject.instant(key: String) = string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    ?.takeIf { it.isAfter(Instant.EPOCH) }

/** "42m", "4h 51m", "1d 8h"; null once the reset has passed. */
internal fun countdown(until: Instant, now: Instant): String? {
    val left = Duration.between(now, until)
    if (left.isNegative || left.isZero) return null
    val minutes = ((left.seconds + 59) / 60).coerceAtLeast(1)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h" + (minutes % 60).let { if (it > 0) " ${it}m" else "" }
        else -> "${minutes / 1440}d" + (minutes % 1440 / 60).let { if (it > 0) " ${it}h" else "" }
    }
}

/** The exact reset moment in the phone's zone, e.g. "Thu, Nov 5, 7:06 AM". */
internal fun localResetTime(at: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", locale).withZone(zone).format(at)

/** Whole-number percent; a sliver above zero never rounds down to a misleading "0%". */
internal fun percent(value: Double): String = when {
    value > 0 && value < 1 -> "<1%"
    value < 100 && value > 99 -> ">99%"
    else -> "${Math.round(value)}%"
}
