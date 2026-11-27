package com.ocrapp.ocr

/**
 * Flattens the Markdown that Unlimited-OCR emits into readable plain text.
 *
 * Used for what Copy and Share give from the Result screen's Rendered view (the Raw
 * view gives the Markdown source) and for the text stored in history search. The
 * original Markdown is kept alongside it, so this is deliberately lossy rather than
 * a reversible transform.
 */
object MarkdownToPlainText {

    private val FENCE = Regex("^\\s*```.*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?")
    private val UNORDERED_ITEM = Regex("^(\\s*)[-*+]\\s+")
    private val ORDERED_ITEM = Regex("^(\\s*)(\\d+)[.)]\\s+")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val BOLD_ITALIC = Regex("(\\*{1,3}|_{1,3})(?=\\S)(.+?)(?<=\\S)\\1")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val STRIKETHROUGH = Regex("~~(.+?)~~")
    private val BLANK_RUN = Regex("\n{3,}")

    /** A cell boundary: a pipe the worker did not escape as `\|` inside a cell. */
    private val CELL_SEPARATOR = Regex("(?<!\\\\)\\|")

    fun convert(markdown: String): String {
        if (markdown.isBlank()) return ""

        val out = StringBuilder()
        var insideFence = false

        for (rawLine in markdown.lineSequence()) {
            if (FENCE.matches(rawLine)) {
                insideFence = !insideFence
                continue
            }
            if (insideFence) {
                out.append(rawLine).append('\n')
                continue
            }
            if (TABLE_DIVIDER.matches(rawLine) && rawLine.contains('-')) {
                continue
            }

            var line = rawLine
                .replace(HEADING, "")
                .replace(BLOCKQUOTE, "")
                .replace(UNORDERED_ITEM) { "${it.groupValues[1]}• " }
                .replace(ORDERED_ITEM) { "${it.groupValues[1]}${it.groupValues[2]}. " }

            if (isTableRow(line)) {
                line = line.trim()
                    .removePrefix("|")
                    .removeSuffix("|")
                    .split(CELL_SEPARATOR)
                    .joinToString("\t") { it.trim().replace("\\|", "|") }
            }

            line = line
                .replace(IMAGE) { it.groupValues[1] }
                .replace(LINK) { it.groupValues[1] }
                .replace(INLINE_CODE) { it.groupValues[1] }
                .replace(STRIKETHROUGH) { it.groupValues[1] }
                .replace(BOLD_ITALIC) { it.groupValues[2] }

            out.append(line.trimEnd()).append('\n')
        }

        return out.toString().replace(BLANK_RUN, "\n\n").trim()
    }

    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 1
    }
}
