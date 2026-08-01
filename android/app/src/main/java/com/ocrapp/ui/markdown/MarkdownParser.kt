package com.ocrapp.ui.markdown

/**
 * A deliberately small Markdown parser covering what Unlimited-OCR actually emits:
 * headings, paragraphs, lists, fenced code, horizontal rules, and GFM tables.
 *
 * Tables are the reason this exists rather than a plain-text view — reproducing table
 * structure is the main thing the model gives you over on-device OCR.
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class ListItem(val marker: String, val text: String, val indent: Int) : MarkdownBlock

    data class CodeBlock(val text: String, val language: String?) : MarkdownBlock

    data class Quote(val text: String) : MarkdownBlock

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlock

    data object Divider : MarkdownBlock
}

object MarkdownParser {

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val FENCE = Regex("^\\s*```\\s*([\\w+-]*)\\s*$")
    private val DIVIDER = Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$")
    private val UNORDERED = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?(\\s*:?-+:?\\s*\\|)+\\s*(:?-+:?\\s*)?\\|?\\s*$")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown.lines()
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
            }
            paragraph.setLength(0)
        }

        while (index < lines.size) {
            val line = lines[index]

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val language = fence.groupValues[1].takeIf { it.isNotBlank() }
                val body = StringBuilder()
                index++
                while (index < lines.size && !FENCE.matches(lines[index])) {
                    body.appendLine(lines[index])
                    index++
                }
                if (index < lines.size) index++ // consume the closing fence
                blocks += MarkdownBlock.CodeBlock(body.toString().trimEnd('\n'), language)
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                index++
                continue
            }

            if (DIVIDER.matches(line)) {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                index++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks += MarkdownBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim(),
                )
                index++
                continue
            }

            // A table needs a header row followed by a divider row; anything else that
            // merely contains pipes is treated as ordinary text.
            val hasDividerNext = index + 1 < lines.size && TABLE_DIVIDER.matches(lines[index + 1])
            if (isTableRow(line) && hasDividerNext) {
                flushParagraph()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && isTableRow(lines[index])) {
                    rows += splitRow(lines[index])
                    index++
                }
                blocks += MarkdownBlock.Table(header, rows)
                continue
            }

            val quote = QUOTE.matchEntire(line)
            if (quote != null) {
                flushParagraph()
                blocks += MarkdownBlock.Quote(quote.groupValues[1].trim())
                index++
                continue
            }

            val unordered = UNORDERED.matchEntire(line)
            if (unordered != null) {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(
                    marker = "•",
                    text = unordered.groupValues[2].trim(),
                    indent = unordered.groupValues[1].length / 2,
                )
                index++
                continue
            }

            val ordered = ORDERED.matchEntire(line)
            if (ordered != null) {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(
                    marker = "${ordered.groupValues[2]}.",
                    text = ordered.groupValues[3].trim(),
                    indent = ordered.groupValues[1].length / 2,
                )
                index++
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(line.trim())
            index++
        }

        flushParagraph()
        return blocks
    }

    private fun isTableRow(line: String): Boolean = line.trim().let {
        it.length > 1 && it.startsWith("|") && it.endsWith("|")
    }

    private fun splitRow(line: String): List<String> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim() }
}
