package ca.mslab.forge.notify

/** Notifications render plain text: drop Markdown markers, flatten tables and code fences. */
internal fun plainText(markdown: String): String = markdown.lineSequence()
    .filterNot { it.trim().matches(Regex("""^\|?\s*:?-{2,}.*""")) || it.trim().startsWith("```") }
    .map { line ->
        line.trim().removePrefix("> ").replace(Regex("""^#{1,6}\s+"""), "").replace(Regex("""^[-*+]\s+"""), "• ")
            .let { if (it.startsWith("|")) it.trim('|').split('|').joinToString(" · ") { cell -> cell.trim() } else it }
            .replace(Regex("""\*\*|__|(?<!\w)[*_](?=\S)|(?<=\S)[*_](?!\w)|`"""), "")
            .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
    }
    .joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

/** "✓ Write tests" / "▶ Run gate" / "○ Update docs", most relevant first, for expanded views. */
internal fun todoLines(todos: List<ca.mslab.forge.data.sync.TodoItem>, limit: Int = 6): List<String> {
    val ordered = todos.filter { it.status == "in_progress" } + todos.filter { it.status != "in_progress" && it.status != "completed" } +
        todos.filter { it.status == "completed" }.takeLast(2)
    return ordered.take(limit).map { item ->
        when (item.status) { "completed" -> "✓ "; "in_progress" -> "▶ "; "failed" -> "✕ "; else -> "○ " } + item.description
    }
}

