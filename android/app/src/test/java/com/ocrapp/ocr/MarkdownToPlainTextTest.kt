package com.ocrapp.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM test — no Android APIs involved. */
class MarkdownToPlainTextTest {

    @Test
    fun `strips heading markers`() {
        assertEquals("Title\n\nSubtitle", MarkdownToPlainText.convert("# Title\n\n### Subtitle"))
    }

    @Test
    fun `strips inline emphasis and code`() {
        val markdown = "This is **bold**, *italic*, `code` and ~~gone~~."
        assertEquals("This is bold, italic, code and gone.", MarkdownToPlainText.convert(markdown))
    }

    @Test
    fun `reduces links and images to their label`() {
        assertEquals(
            "See the chart here.",
            MarkdownToPlainText.convert("See the ![chart](img.png) [here](http://x.test)."),
        )
    }

    @Test
    fun `converts tables to tab separated rows and drops the divider`() {
        val markdown = """
            | Item | Qty |
            | --- | ---: |
            | Bolt | 12 |
        """.trimIndent()

        assertEquals("Item\tQty\nBolt\t12", MarkdownToPlainText.convert(markdown))
    }

    @Test
    fun `normalizes list markers`() {
        val markdown = "- first\n- second\n1. third"
        assertEquals("• first\n• second\n1. third", MarkdownToPlainText.convert(markdown))
    }

    @Test
    fun `keeps fenced code content but drops the fences`() {
        val markdown = "```python\nprint('hi')\n```"
        assertEquals("print('hi')", MarkdownToPlainText.convert(markdown))
    }

    @Test
    fun `collapses runs of blank lines`() {
        assertEquals("a\n\nb", MarkdownToPlainText.convert("a\n\n\n\n\nb"))
    }

    @Test
    fun `blank input yields empty string`() {
        assertEquals("", MarkdownToPlainText.convert("   \n  \n"))
    }

    @Test
    fun `strips blockquote markers`() {
        assertEquals("quoted line", MarkdownToPlainText.convert("> quoted line"))
    }
}
