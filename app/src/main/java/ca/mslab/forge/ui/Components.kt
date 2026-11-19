package ca.mslab.forge.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.mslab.forge.ui.theme.ForgeCodeFont
import ca.mslab.forge.ui.theme.ForgeTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Icon, label and colors for a task or session state. */
internal data class StateVisual(val label: String, val icon: ImageVector, val color: Color, val container: Color, val active: Boolean = false)

private val RUNNING_STATES = setOf("RUNNING", "STARTING", "IN_PROGRESS", "SCHEDULING")

@Composable
internal fun stateVisual(state: String?): StateVisual {
    val status = ForgeTheme.status
    val scheme = MaterialTheme.colorScheme
    val value = state.orEmpty().uppercase()
    return when (value) {
        "RUNNING", "IN_PROGRESS" -> StateVisual(value.label(), Icons.Rounded.PlayCircle, status.info, status.infoContainer, true)
        "STARTING", "SCHEDULING" -> StateVisual(value.label(), Icons.Rounded.HourglassTop, status.info, status.infoContainer, true)
        "WAITING_FOR_INPUT" -> StateVisual("Your turn", Icons.Rounded.BackHand, status.warning, status.warningContainer)
        "REVIEW" -> StateVisual("Review", Icons.Rounded.RateReview, scheme.primary, scheme.primaryContainer)
        "BLOCKED" -> StateVisual("Blocked", Icons.Rounded.Block, status.warning, status.warningContainer)
        "COMPLETED", "DONE" -> StateVisual(value.label(), Icons.Rounded.CheckCircle, status.success, status.successContainer)
        "FAILED" -> StateVisual("Failed", Icons.Rounded.Error, scheme.error, scheme.errorContainer)
        "CANCELLED" -> StateVisual("Cancelled", Icons.Rounded.Cancel, status.neutral, status.neutralContainer)
        "CREATED", "TODO", "BACKLOG" -> StateVisual(value.label(), Icons.Rounded.RadioButtonUnchecked, status.neutral, status.neutralContainer)
        "" -> StateVisual("Unknown", Icons.Rounded.QuestionMark, status.neutral, status.neutralContainer)
        else -> StateVisual(value.label(), Icons.Rounded.Circle, status.neutral, status.neutralContainer, value in RUNNING_STATES)
    }
}

@Composable
internal fun StatusPill(visual: StateVisual, modifier: Modifier = Modifier) {
    Surface(modifier, color = visual.container, contentColor = visual.color, shape = CircleShape) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (visual.active) PulseDot(visual.color, Modifier.padding(horizontal = 3.dp))
            else Icon(visual.icon, null, Modifier.size(14.dp))
            Text(visual.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A small dot that breathes while something is live. */
@Composable
internal fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = true) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        value
    } else 1f
    Box(modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** Resolves an agent profile id to its provider logo; installed by the app shell. */
internal val LocalAgentIcon = androidx.compose.runtime.staticCompositionLocalOf<(String?) -> ImageVector?> { { null } }

/** The provider's own logo (Claude, Codex…) in its own colors; a generic spark until it is known. */
@Composable
internal fun AgentMark(profileId: String?, modifier: Modifier = Modifier, size: Dp = 16.dp,
    fallbackTint: Color = MaterialTheme.colorScheme.primary) {
    val logo = LocalAgentIcon.current(profileId)
    if (logo != null) androidx.compose.foundation.Image(logo, null, modifier.size(size))
    else Icon(Icons.Rounded.AutoAwesome, null, modifier.size(size), tint = fallbackTint)
}

/** Icon + text metadata, e.g. agent or executor name. */
@Composable
internal fun MetaItem(icon: ImageVector, text: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant, mono: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) ForgeCodeFont else null)
    }
}

/** Rounded tag used for repositories, branches and counts. */
@Composable
internal fun Tag(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant, mono: Boolean = false) {
    Surface(modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(13.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = if (mono) ForgeCodeFont else null)
        }
    }
}

/** Git additions/deletions in diff colors. */
@Composable
internal fun DiffStat(additions: Int, deletions: Int, modifier: Modifier = Modifier) {
    val status = ForgeTheme.status
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+${compactCount(additions)}", style = MaterialTheme.typography.labelMedium, color = status.success, fontFamily = ForgeCodeFont)
        Text("−${compactCount(deletions)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontFamily = ForgeCodeFont)
    }
}

@Composable
internal fun ForgeTopBar(
    title: String, subtitle: (@Composable () -> Unit)? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
        }
        actions()
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape) { Icon(icon, null, Modifier.padding(16.dp).size(32.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
internal fun ErrorNotice(message: String, modifier: Modifier = Modifier) {
    Notice(message, Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, modifier)
}

@Composable
internal fun InfoNotice(message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Info) {
    Notice(message, icon, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, modifier)
}

@Composable
private fun Notice(message: String, icon: ImageVector, container: Color, content: Color, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Circular icon badge used as an avatar for agents, files and settings rows. */
@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = content)
    }
}

internal fun String.label() = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

internal fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "${value / 1000}k"
    value >= 1_000 -> "%.1fk".format(value / 1000.0)
    else -> "$value"
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("MMM d")

/** "now", "5m", "3h", "2d", then a short date. Unparseable input yields an empty string. */
internal fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
    val instant = iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val age = Duration.between(instant, now)
    return when {
        age.seconds < 60 -> "now"
        age.toMinutes() < 60 -> "${age.toMinutes()}m"
        age.toHours() < 24 -> "${age.toHours()}h"
        age.toDays() < 7 -> "${age.toDays()}d"
        else -> dateFormat.format(instant.atZone(ZoneId.systemDefault()))
    }
}

internal fun clockTime(iso: String?): String {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    return clockFormat.format(instant.atZone(ZoneId.systemDefault()))
}
