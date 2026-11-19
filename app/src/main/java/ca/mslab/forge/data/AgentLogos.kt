package ca.mslab.forge.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider logos served by Kandev (`/api/v1/agents/{agent}/logo`), kept on disk so they paint
 * instantly and offline. Missing logos are remembered too, so a 404 is asked once per process.
 */
internal class AgentLogos(private val scope: CoroutineScope, private val directory: () -> File?, private val api: () -> KandevApi?) {
    private val icons = mutableStateMapOf<String, ImageVector>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    fun icon(agent: String, dark: Boolean): ImageVector? {
        if (agent.isBlank()) return null
        val key = "$agent-${if (dark) "dark" else "light"}"
        icons[key]?.let { return it }
        if (asked.add(key)) scope.launch { load(key, agent, dark) }
        return null
    }

    private suspend fun load(key: String, agent: String, dark: Boolean) {
        val file = directory()?.let { File(it, digest(key) + ".svg") }
        val saved = withContext(Dispatchers.IO) { file?.takeIf { it.isFile }?.readText() }
        saved?.let { parseSvgIcon(it) }?.let { icons[key] = it; return }
        val svg = try { api()?.agentLogo(agent, if (dark) "dark" else "light") ?: return }
        catch (e: Exception) {
            // Offline or server hiccup: ask again in a minute rather than on every frame.
            kotlinx.coroutines.delay(60_000); asked.remove(key); return
        }
        parseSvgIcon(svg)?.let { icon ->
            icons[key] = icon
            withContext(Dispatchers.IO) { runCatching { file?.parentFile?.mkdirs(); file?.writeText(svg) } }
        }
    }

    fun clear() { icons.clear(); asked.clear() }
}

private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
// Masks, clip paths and defs in these logos only restate the full canvas; drawing their shapes would be wrong.
private val HIDDEN = Regex("""<(mask|clipPath|defs)\b.*?</\1>""", RegexOption.DOT_MATCHES_ALL)
private val ROOT = Regex("""<svg\b([^>]*)>""")
private val PATH = Regex("""<path\b([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)

private fun attribute(tag: String, name: String): String? =
    Regex("""(?:^|\s)$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

private fun svgColor(value: String): Color? = when (val v = value.trim().lowercase()) {
    "none", "transparent" -> null
    "white" -> Color.White
    "black", "currentcolor" -> Color.Black
    else -> runCatching {
        val hex = v.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        Color(0xFF000000 or hex.toLong(16))
    }.getOrNull()
}

/** The small subset of SVG Kandev's logos use: a viewBox and filled paths. Anything else yields null. */
internal fun parseSvgIcon(svg: String): ImageVector? = runCatching {
    val clean = HIDDEN.replace(COMMENT.replace(svg, ""), "")
    val root = ROOT.find(clean)!!.groupValues[1]
    val box = attribute(root, "viewBox")!!.trim().split(Regex("[\\s,]+")).map { it.toFloat() }
    val (left, top, width, height) = box
    val rootFill = attribute(root, "fill")
    val rootRule = attribute(root, "fill-rule")
    val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = (24 * height / width).dp,
        viewportWidth = width, viewportHeight = height)
    builder.addGroup(translationX = -left, translationY = -top)
    var drawn = 0
    for (match in PATH.findAll(clean)) {
        val tag = match.groupValues[1]
        val data = attribute(tag, "d")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val fill = svgColor(attribute(tag, "fill") ?: rootFill ?: "#000000") ?: continue
        val rule = attribute(tag, "fill-rule") ?: rootRule
        builder.addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(fill),
            pathFillType = if (rule == "evenodd") PathFillType.EvenOdd else PathFillType.NonZero)
        drawn++
    }
    builder.clearGroup()
    if (drawn == 0) null else builder.build()
}.getOrNull()
