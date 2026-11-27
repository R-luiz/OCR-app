package com.ocrapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownItemsTest {

    private fun build(markdown: String) = MarkdownItems.build(MarkdownParser.parse(markdown))

    @Test
    fun `splits a table into a header item and one item per row`() {
        val items = build(
            """
            | Item | Qty |
            | --- | --- |
            | Bolt | 12 |
            | Nut | 30 |
            | Washer | 5 |
            """.trimIndent(),
        )

        val rows = items.filterIsInstance<MarkdownItem.TableRow>()
        assertEquals(items.size, rows.size)
        assertEquals(listOf(true, false, false, false), rows.map { it.isHeader })
        assertEquals(listOf(false, false, true, true), rows.map { it.hasDivider })
        assertEquals(listOf(false, false, false, true), rows.map { it.isLast })
        assertEquals(listOf("Nut", "30"), rows[2].cells.map { it.text })
    }

    @Test
    fun `pads every row of a ragged table to its widest row`() {
        val items = build(
            """
            | A | B |
            | --- | --- |
            | 1 |
            | 1 | 2 | 3 |
            """.trimIndent(),
        )

        val rows = items.filterIsInstance<MarkdownItem.TableRow>()
        assertEquals(listOf(3, 3, 3), rows.map { it.cells.size })
        assertEquals(listOf("1", "", ""), rows[1].cells.map { it.text })
        assertEquals(listOf("A", "B", ""), rows[0].cells.map { it.text })
    }

    @Test
    fun `numbers tables in order so each keeps its own scroll position`() {
        val items = build(
            """
            | A |
            | --- |
            | 1 |

            Between.

            | B |
            | --- |
            """.trimIndent(),
        )

        val rows = items.filterIsInstance<MarkdownItem.TableRow>()
        assertEquals(listOf(0, 0, 1), rows.map { it.table })
        // A table without body rows is its header alone, which both opens and closes it.
        assertTrue(rows[2].isHeader && rows[2].isLast)
        assertTrue(items[2] is MarkdownItem.Paragraph)
    }

    @Test
    fun `applies inline emphasis while building, keeping the block order`() {
        val items = build("# **Total**\n\n- *one*\n\n```\nraw **text**\n```\n\n---")

        assertEquals(
            listOf(
                MarkdownItem.Heading::class,
                MarkdownItem.ListItem::class,
                MarkdownItem.Code::class,
                MarkdownItem.Divider::class,
            ),
            items.map { it::class },
        )
        val heading = items[0] as MarkdownItem.Heading
        assertEquals("Total", heading.text.text)
        assertTrue(heading.text.spanStyles.isNotEmpty())
        assertEquals("one", (items[1] as MarkdownItem.ListItem).text.text)
        assertEquals("raw **text**", (items[2] as MarkdownItem.Code).text)
    }
}
