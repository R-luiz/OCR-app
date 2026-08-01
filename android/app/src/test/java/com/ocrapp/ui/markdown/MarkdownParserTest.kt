package com.ocrapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings with their level`() {
        val blocks = MarkdownParser.parse("# One\n### Three")

        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "One"),
                MarkdownBlock.Heading(3, "Three"),
            ),
            blocks,
        )
    }

    @Test
    fun `joins wrapped lines into one paragraph`() {
        val blocks = MarkdownParser.parse("first line\nsecond line\n\nnew para")

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("first line second line"),
                MarkdownBlock.Paragraph("new para"),
            ),
            blocks,
        )
    }

    @Test
    fun `parses a gfm table into header and rows`() {
        val markdown = """
            | Item | Qty | Price |
            | --- | ---: | :---: |
            | Bolt | 12 | 0.40 |
            | Nut | 30 | 0.10 |
        """.trimIndent()

        val table = MarkdownParser.parse(markdown).single() as MarkdownBlock.Table

        assertEquals(listOf("Item", "Qty", "Price"), table.header)
        assertEquals(
            listOf(listOf("Bolt", "12", "0.40"), listOf("Nut", "30", "0.10")),
            table.rows,
        )
    }

    @Test
    fun `pipes without a divider row stay ordinary text`() {
        val blocks = MarkdownParser.parse("| not | a table |\njust text")

        assertTrue(blocks.toString(), blocks.none { it is MarkdownBlock.Table })
    }

    @Test
    fun `parses fenced code with its language`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\nval y = 2\n```")

        assertEquals(
            listOf(MarkdownBlock.CodeBlock("val x = 1\nval y = 2", "kotlin")),
            blocks,
        )
    }

    @Test
    fun `handles an unterminated fence without looping`() {
        val blocks = MarkdownParser.parse("```\ndangling")

        assertEquals(listOf(MarkdownBlock.CodeBlock("dangling", null)), blocks)
    }

    @Test
    fun `parses ordered and unordered list items`() {
        val blocks = MarkdownParser.parse("- alpha\n2. beta")

        assertEquals(
            listOf(
                MarkdownBlock.ListItem("•", "alpha", 0),
                MarkdownBlock.ListItem("2.", "beta", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `records nesting depth for indented items`() {
        val item = MarkdownParser.parse("    - deep").single() as MarkdownBlock.ListItem

        assertEquals(2, item.indent)
    }

    @Test
    fun `parses horizontal rules and blockquotes`() {
        val blocks = MarkdownParser.parse("---\n> quoted")

        assertEquals(
            listOf(MarkdownBlock.Divider, MarkdownBlock.Quote("quoted")),
            blocks,
        )
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), MarkdownParser.parse(""))
    }
}
