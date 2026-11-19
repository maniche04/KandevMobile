package ca.mslab.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun tableBecomesItsOwnBlockBetweenProse() {
        val blocks = markdownBlocks("Memory use:\n\n| Service | RAM | Note |\n|---|:---:|---|\n| VM | 3.9 GB | big |\n| HA | 4 GB |\n\nDone.")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Prose("Memory use:"), blocks[0])
        val table = blocks[1] as MarkdownBlock.Table
        assertEquals(listOf("Service", "RAM", "Note"), table.header)
        // Short rows are padded so every column lines up.
        assertEquals(listOf(listOf("VM", "3.9 GB", "big"), listOf("HA", "4 GB", "")), table.rows)
        assertEquals(MarkdownBlock.Prose("Done."), blocks[2])
    }

    @Test fun pipesInCodeFencesAndEscapesAreNotTables() {
        val fenced = "```\n| a | b |\n|---|---|\n```"
        assertEquals(listOf(MarkdownBlock.Prose(fenced)), markdownBlocks(fenced))
        val table = markdownBlocks("| cmd | meaning |\n| --- | --- |\n| `a \\| b` | pipe |")[0] as MarkdownBlock.Table
        assertEquals("`a | b`", table.rows[0][0])
    }

    @Test fun plainTextWithPipesStaysProse() {
        assertEquals(listOf(MarkdownBlock.Prose("a | b\n---")), markdownBlocks("a | b\n---"))
    }
}
