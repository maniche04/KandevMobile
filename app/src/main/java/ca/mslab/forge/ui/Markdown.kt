package ca.mslab.forge.ui

import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.LruCache
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ca.mslab.forge.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import android.text.SpannableStringBuilder
import android.text.Spanned as AndroidSpanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.View
import ca.mslab.forge.data.isOpenableFilePath
import ca.mslab.forge.data.workspaceLinkPath
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ca.mslab.forge.ui.theme.ForgeCodeFont

/** Native text, with parsing off the UI thread and a bounded rendered-span cache. */
internal class MarkdownRenderer(context: Context) {
    private val cache = object : LruCache<String, Spanned>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Spanned): Int = value.length * 2
    }
    private val markwon = Markwon.builder(context.applicationContext)
        .usePlugin(TablePlugin.create(context.applicationContext))
        .usePlugin(StrikethroughPlugin.create()).usePlugin(TaskListPlugin.create(context.applicationContext))
        // Bare URLs in agent output become tappable links, like the web client.
        .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.codeTypeface(context.resources.getFont(R.font.jetbrains_mono))
            }
        }).build()
    fun cached(content: String): Spanned? = cache.get(content)
    suspend fun render(content: String): Spanned = withContext(Dispatchers.Default) {
        synchronized(markwon) {
            cache.get(content) ?: markwon.toMarkdown(content).also { cache.put(content, it) }
        }
    }
}
internal val LocalMarkdown = staticCompositionLocalOf<MarkdownRenderer> { error("Markdown renderer not installed") }

/** Opens a file path mentioned in agent output (Files tab: image preview, or file with its diff). */
internal val LocalFileOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

private val FILE_PATH = Regex("""(?<![\w/.:-])(?:~|\.{1,2})?/?(?:[\w.@+-]+/)+[\w.@+-]+\.[A-Za-z0-9]{1,8}(?::\d+)?""")

/** Routes explicit workspace links internally and adds links to file paths in prose and code. */
internal fun withFileLinks(text: CharSequence, open: (String) -> Unit, color: Int): CharSequence {
    val spannable = SpannableStringBuilder(text)
    val links = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
    fun linked(start: Int, end: Int) = spannable.getSpans(start, end, ClickableSpan::class.java).isNotEmpty()
    fun span(start: Int, end: Int, path: String) {
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = open(path)
            override fun updateDrawState(paint: TextPaint) { paint.color = color; paint.isUnderlineText = true }
        }, start, end, AndroidSpanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Markwon's LinkSpan extends URLSpan. Replace local destinations, keeping web links external.
    links.forEach { link ->
        workspaceLinkPath(link.url)?.let { path ->
            val start = spannable.getSpanStart(link); val end = spannable.getSpanEnd(link)
            spannable.removeSpan(link); span(start, end, path)
        }
    }
    fun add(start: Int, end: Int, raw: String) {
        val path = raw.substringBefore(':').trim('`')
        if (!isOpenableFilePath(path) || linked(start, end)) return
        span(start, end, path)
    }
    FILE_PATH.findAll(spannable).forEach { add(it.range.first, it.range.last + 1, it.value) }
    // Single file names in inline code (`Foo.kt`) are links too.
    spannable.getSpans(0, spannable.length, io.noties.markwon.core.spans.CodeSpan::class.java).forEach { span ->
        val start = spannable.getSpanStart(span); val end = spannable.getSpanEnd(span)
        val value = spannable.substring(start, end)
        if (!value.contains('/')) add(start, end, value)
    }
    return spannable
}

/** Markdown split so tables can render natively: spans can't lay out wide tables on a phone. */
internal sealed interface MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun tableCells(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    // Split on pipes that aren't escaped (\|), then unescape.
    return trimmed.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
}

/** Finds GFM tables outside fenced code; everything else stays as prose for the span renderer. */
internal fun markdownBlocks(content: String): List<MarkdownBlock> {
    if (!content.contains('|')) return listOf(MarkdownBlock.Prose(content))
    val lines = content.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = StringBuilder()
    fun flush() { if (prose.isNotBlank()) blocks += MarkdownBlock.Prose(prose.toString().trim('\n')); prose.clear() }
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
        val next = lines.getOrNull(i + 1)
        if (!fenced && line.contains('|') && next != null && next.contains('|') && TABLE_DIVIDER.matches(next)) {
            val header = tableCells(line)
            var j = i + 2
            val rows = mutableListOf<List<String>>()
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                rows += tableCells(lines[j]).let { cells -> List(header.size) { cells.getOrElse(it) { "" } } }
                j++
            }
            flush(); blocks += MarkdownBlock.Table(header, rows)
            i = j; continue
        }
        prose.append(line).append('\n'); i++
    }
    flush()
    return blocks
}

@Composable
internal fun MarkdownText(content: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val blocks = remember(content) { markdownBlocks(content) }
    if (blocks.size == 1 && blocks[0] is MarkdownBlock.Prose) { ProseText(content, modifier, maxLines); return }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Collapsed long messages show their opening prose only.
        val shown = if (maxLines == Int.MAX_VALUE) blocks else blocks.take(1)
        shown.forEach { block ->
            when (block) {
                is MarkdownBlock.Prose -> ProseText(block.text, Modifier.fillMaxWidth(), maxLines)
                is MarkdownBlock.Table -> MarkdownTable(block)
            }
        }
    }
}

/** A real grid: columns sized to their content (within limits), scrolling sideways when wide. */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val body = MaterialTheme.typography.bodySmall
    val head = body.copy(fontWeight = FontWeight.SemiBold)
    val widths = remember(table, density) {
        table.header.indices.map { column ->
            val widest = (listOf(table.header[column] to head) + table.rows.map { it[column] to body }).maxOf { (text, style) ->
                measurer.measure(inlineMarkdown(text), style, maxLines = 1).size.width
            }
            with(density) { (widest.toDp() + 20.dp).coerceIn(56.dp, 240.dp) }
        }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = MaterialTheme.shapes.extraSmall, border = BorderStroke(1.dp, border), color = Color.Transparent) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(table.header) + table.rows).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, b -> a + b }), color = border)
                Row(Modifier.height(IntrinsicSize.Min)
                    .background(if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    row.forEachIndexed { column, cell ->
                        SelectionContainer {
                            Text(inlineMarkdown(cell), style = if (index == 0) head else body,
                                modifier = Modifier.width(widths[column]).fillMaxHeight().padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Bold, italic and `code` inside a table cell. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|<br\s*/?>""")
    var last = 0
    for (match in pattern.findAll(text)) {
        append(text.substring(last, match.range.first))
        val (bold, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = ForgeCodeFont, background = Color(0x1A808080))) { append(code) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            else -> append('\n')
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
private fun ProseText(content: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val context = LocalContext.current
    val renderer = LocalMarkdown.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val opener = LocalFileOpener.current
    val rendered by produceState<CharSequence>(renderer.cached(content) ?: content, renderer, content, opener) {
        val spanned = renderer.render(content)
        value = if (opener == null) spanned else withContext(Dispatchers.Default) { withFileLinks(spanned, opener, linkColor) }
    }
    AndroidView(modifier = modifier, factory = {
        TextView(context).apply {
            typeface = context.resources.getFont(R.font.hanken_grotesk)
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        }
    }, update = {
        it.setTextColor(color); it.setLinkTextColor(linkColor); it.maxLines = maxLines
        if (it.text !== rendered) it.text = rendered
    })
}
