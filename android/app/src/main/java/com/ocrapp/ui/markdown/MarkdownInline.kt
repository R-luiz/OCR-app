package com.ocrapp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Applies inline Markdown emphasis to a single line.
 *
 * Handles the four inline forms the model produces — bold, italic, inline code, and
 * strikethrough — plus link and image syntax, which are reduced to their label text
 * because a scanned document has nothing to navigate to.
 */
object MarkdownInline {

    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")

    private data class Token(val regex: Regex, val style: SpanStyle)

    private val TOKENS = listOf(
        Token(Regex("\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
        Token(Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold)),
        Token(Regex("__(?=\\S)(.+?)(?<=\\S)__"), SpanStyle(fontWeight = FontWeight.Bold)),
        Token(Regex("~~(?=\\S)(.+?)(?<=\\S)~~"), SpanStyle(textDecoration = TextDecoration.LineThrough)),
        Token(Regex("\\*(?=\\S)(.+?)(?<=\\S)\\*"), SpanStyle(fontStyle = FontStyle.Italic)),
        Token(Regex("_(?=\\S)(.+?)(?<=\\S)_"), SpanStyle(fontStyle = FontStyle.Italic)),
        Token(Regex("`([^`]+)`"), SpanStyle(fontFamily = FontFamily.Monospace)),
    )

    fun annotate(text: String): AnnotatedString {
        val flattened = text
            .replace(IMAGE) { it.groupValues[1] }
            .replace(LINK) { it.groupValues[1] }
        return buildAnnotatedString { append(flattened, TOKENS) }
    }

    /**
     * Recursively splits on the first matching token so nested emphasis (bold inside
     * italic, say) keeps both styles instead of the outer one winning.
     */
    private fun androidx.compose.ui.text.AnnotatedString.Builder.append(
        text: String,
        tokens: List<Token>,
    ) {
        if (tokens.isEmpty()) {
            append(text)
            return
        }
        val token = tokens.first()
        val remaining = tokens.drop(1)
        var cursor = 0

        for (match in token.regex.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first), remaining)
            }
            pushStyle(token.style)
            append(match.groupValues[1], remaining)
            pop()
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor), remaining)
        }
    }
}
