package ca.mslab.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.mslab.forge.data.*
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Ticks once every 30 s while on screen, for reset countdowns; nothing runs when home is hidden. */
@Composable
private fun rememberNow(): Instant {
    val now by produceState(Instant.now()) { while (true) { delay(30_000); value = Instant.now() } }
    return now
}

/** The report is older than two polls (or 30 minutes, whichever is longer) by the server's or phone's clock. */
internal fun usageStale(report: UsageReport, receivedAt: Long?, now: Instant): Boolean {
    val limit = maxOf(30L, 2L * (report.pollMinutes ?: 10)) * 60
    val generated = report.generatedAt ?: receivedAt?.let(Instant::ofEpochMilli) ?: return true
    val received = receivedAt?.let(Instant::ofEpochMilli) ?: generated
    return now.epochSecond - minOf(generated, received).epochSecond > limit
}

/** Remaining-quota tone: calm until a quarter is left, amber, then red under a tenth. */
@Composable
private fun remainingColor(remaining: Double): Color = when {
    remaining <= 10 -> MaterialTheme.colorScheme.error
    remaining <= 25 -> ForgeTheme.status.warning
    else -> MaterialTheme.colorScheme.onSurface
}

/** Everything the strip and its sheet show, so both render without a view model (previews, UI tests). */
internal data class UsageView(val report: UsageReport?, val receivedAt: Long?, val problem: UsageProblem?, val loading: Boolean)

/** The provider's logo from Kandev, or a neutral usage icon. */
internal typealias UsageLogo = (provider: String) -> ImageVector?

@Composable
private fun ProviderLogo(logos: UsageLogo, provider: String, size: androidx.compose.ui.unit.Dp) {
    val logo = logos(provider)
    if (logo != null) Image(logo, null, Modifier.size(size))
    else Icon(Icons.Rounded.DataUsage, null, Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One line of provider chips directly above the task list: remaining quota per 5-hour and weekly window,
 * with the time to reset beside it. Hidden entirely until there is something real to show.
 */
@Composable
internal fun UsageStrip(model: ConnectionViewModel, modifier: Modifier = Modifier) {
    val usage = model.usage
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val logos: UsageLogo = remember(model, dark) { { id -> providerLogoAgent(id)?.let { model.logos.icon(it, dark) } } }
    UsageStrip(UsageView(usage.report, usage.receivedAt, usage.problem, usage.loading), usage::refreshNow, logos, modifier)
}

@Composable
internal fun UsageStrip(usage: UsageView, onRefresh: () -> Unit, logos: UsageLogo, modifier: Modifier = Modifier,
    now: Instant = rememberNow()) {
    val report = usage.report
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = report?.providers?.filter { it.windows.isNotEmpty() }.orEmpty()
    AnimatedVisibility(shown.isNotEmpty(), modifier) {
        val stale = report != null && usageStale(report, usage.receivedAt, now)
        LazyRow(Modifier.fillMaxWidth().height(40.dp).testTag("usage-strip"),
            contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            items(shown, key = { it.id }) { provider -> UsageChip(logos, provider, now, stale) { open = true } }
            if (stale || usage.problem != null) item(key = "state") {
                Surface(onClick = { open = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.height(32.dp)) {
                    Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Icon(if (usage.problem == UsageProblem.Offline) Icons.Rounded.CloudOff else Icons.Rounded.History,
                            if (stale) "Usage data is out of date" else "Usage couldn't refresh",
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
    if (open && report != null) UsageSheet(usage, report, now, onRefresh, logos) { open = false }
}

@Composable
private fun UsageChip(logos: UsageLogo, provider: ProviderQuota, now: Instant, stale: Boolean, onClick: () -> Unit) {
    // Without a 5-hour or weekly window, the tightest other window stands in so the chip still says something true.
    val windows = provider.headline.ifEmpty {
        listOfNotNull(provider.windows.filter { !it.scoped }.ifEmpty { provider.windows }.minByOrNull { it.remainingPercent })
    }
    val spoken = windows.joinToString("; ") { w ->
        "${w.title} ${percent(w.remainingPercent)} left" + (w.resetAt?.let { countdown(it, now) }?.let { ", resets in $it" } ?: "")
    }
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.height(32.dp).alpha(if (stale) 0.6f else 1f).testTag("usage-chip-${provider.id}")
            .semantics { contentDescription = "${provider.name}: $spoken. Opens usage details." }) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProviderLogo(logos, provider.id, 16.dp)
            windows.forEachIndexed { index, window ->
                if (index > 0) Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                ChipWindow(window, now)
            }
        }
    }
}

@Composable
private fun ChipWindow(window: QuotaWindow, now: Instant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(window.short, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(percent(window.remainingPercent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = remainingColor(window.remainingPercent), maxLines = 1)
        window.resetAt?.let { countdown(it, now) }?.let { left ->
            Icon(Icons.Rounded.Schedule, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.outline)
            Text(left, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

/** Every provider's windows in full: remaining vs used, reset countdown and exact local time, freshness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSheet(usage: UsageView, report: UsageReport, now: Instant, onRefresh: () -> Unit, logos: UsageLogo,
    onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val stale = usageStale(report, usage.receivedAt, now)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
            .testTag("usage-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Provider usage", style = MaterialTheme.typography.titleLarge)
                    val updated = report.generatedAt ?: usage.receivedAt?.let(Instant::ofEpochMilli)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PulseDot(if (stale) ForgeTheme.status.warning else ForgeTheme.status.success, size = 7.dp, pulsing = false)
                        Text((if (stale) "Out of date · " else "") +
                            (updated?.let { relativeTime(it.toString(), now) }?.let { if (it == "now") "Checked just now" else "Checked $it ago" }
                                ?: "Not checked yet"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledTonalButton(onClick = onRefresh, enabled = !usage.loading,
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    if (usage.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Refresh")
                }
            }
            usage.problem?.let { problem ->
                Text(when (problem) {
                    UsageProblem.Offline -> "Can't reach the server. Showing the last saved usage."
                    UsageProblem.Denied -> "The server refused the usage request. Showing the last saved usage."
                    UsageProblem.Server -> "The usage plugin had a problem. Showing the last saved usage."
                    UsageProblem.Invalid -> "The usage plugin sent data this app can't read."
                    UsageProblem.NotInstalled -> "The Provider Usage plugin isn't installed on this server."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            report.providers.forEach { ProviderCard(logos, it, now, zone) }
            report.unavailable.forEach { issue ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderLogo(logos, issue.id, 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(issue.name, style = MaterialTheme.typography.titleSmall)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Read-only quotas from the Provider Usage plugin on your Kandev server. Updates at most every " +
                "${report.pollMinutes ?: 10} minutes; nothing runs in the background on this phone.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ProviderCard(logos: UsageLogo, provider: ProviderQuota, now: Instant, zone: ZoneId) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderLogo(logos, provider.id, 22.dp)
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                listOfNotNull(provider.plan, provider.account).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                provider.fetchedAt?.let {
                    Text(relativeTime(it.toString(), now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (provider.windows.isEmpty()) Text("No quota windows reported.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            provider.windows.forEach { WindowDetail(it, now, zone) }
        }
    }
}

@Composable
private fun WindowDetail(window: QuotaWindow, now: Instant, zone: ZoneId) {
    val remaining = window.remainingPercent
    val tone = remainingColor(remaining)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(window.title + if (window.scoped && window.kind != WindowKind.Other) " · ${window.kind.title.lowercase()}" else "",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${percent(remaining)} left", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = if (tone == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.primary else tone)
        }
        LinearProgressIndicator(progress = { (remaining / 100).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (tone == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.primary else tone,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round, gapSize = 0.dp,
            drawStopIndicator = {})
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${percent(window.usedPercent)} used", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = ForgeCodeFont)
            val reset = window.resetAt
            val resetLine = when {
                reset != null -> (countdown(reset, now)?.let { "Resets in $it" } ?: "Reset due") + " · " + localResetTime(reset, zone)
                window.resetText != null -> "Resets ${window.resetText}"
                else -> null
            }
            if (resetLine != null) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                Text(resetLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
