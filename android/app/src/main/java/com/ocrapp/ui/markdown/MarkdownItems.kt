package com.ocrapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString

/**
 * One item of the rendered document's lazy list, with its inline emphasis already
 * applied.
 *
 * A table is split into one item per row, so scrolling a long table into view composes
 * the rows on screen rather than every cell at once. The rows of one table share its
 * [TableRow.table] number, and with it one horizontal scroll position.
 */
sealed interface MarkdownItem {
    data class Heading(val level: Int, val text: AnnotatedString) : MarkdownItem

    data class Paragraph(val text: AnnotatedString) : MarkdownItem

    data class ListItem(val marker: String, val text: AnnotatedString, val indent: Int) : MarkdownItem

    data class Code(val text: String) : MarkdownItem

    data class Quote(val text: AnnotatedString) : MarkdownItem

    /**
     * A table's header (always its first row) or one of its body rows. [cells] has as
     * many entries as the table's widest row, header included, so that every row is as
     * wide as the others and the columns line up.
     */
    data class TableRow(
        val table: Int,
        val cells: List<AnnotatedString>,
        val isHeader: Boolean,
        /** A body row below another body row; the header is set apart by its background. */
        val hasDivider: Boolean,
        val isLast: Boolean,
    ) : MarkdownItem

    data object Divider : MarkdownItem
}

object MarkdownItems {

    /**
     * Turns parsed [blocks] into list items, applying inline emphasis on the way. Meant
     * to run off the main thread together with the parse: the regex passes behind
     * [MarkdownInline.annotate] would otherwise run in composition, a table at a time.
     */
    fun build(blocks: List<MarkdownBlock>): List<MarkdownItem> {
        val items = ArrayList<MarkdownItem>(blocks.size)
        var tableCount = 0
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> items += MarkdownItem.Heading(block.level, MarkdownInline.annotate(block.text))
                is MarkdownBlock.Paragraph -> items += MarkdownItem.Paragraph(MarkdownInline.annotate(block.text))
                is MarkdownBlock.ListItem ->
                    items += MarkdownItem.ListItem(block.marker, MarkdownInline.annotate(block.text), block.indent)
                is MarkdownBlock.CodeBlock -> items += MarkdownItem.Code(block.text)
                is MarkdownBlock.Quote -> items += MarkdownItem.Quote(MarkdownInline.annotate(block.text))
                is MarkdownBlock.Table -> addTable(items, tableCount++, block)
                MarkdownBlock.Divider -> items += MarkdownItem.Divider
            }
        }
        return items
    }

    private fun addTable(items: MutableList<MarkdownItem>, table: Int, block: MarkdownBlock.Table) {
        // Ragged rows are common in OCR output; pad them all to the widest.
        val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
        fun cells(row: List<String>) = List(columns) { MarkdownInline.annotate(row.getOrElse(it) { "" }) }

        items += MarkdownItem.TableRow(
            table = table,
            cells = cells(block.header),
            isHeader = true,
            hasDivider = false,
            isLast = block.rows.isEmpty(),
        )
        block.rows.forEachIndexed { index, row ->
            items += MarkdownItem.TableRow(
                table = table,
                cells = cells(row),
                isHeader = false,
                hasDivider = index > 0,
                isLast = index == block.rows.lastIndex,
            )
        }
    }
}
