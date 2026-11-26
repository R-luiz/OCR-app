package com.ocrapp.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ocrapp.ui.theme.MonoTextStyle
import com.ocrapp.ui.theme.TableCellStyle
import com.ocrapp.ui.theme.TableHeaderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders Markdown as a scrolling list of blocks.
 *
 * A parsed document can run to thousands of blocks, so it is parsed off the main thread
 * and only the blocks on screen are composed. Wide tables scroll horizontally instead
 * of squeezing their text.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var blocks by remember(markdown) { mutableStateOf(emptyList<MarkdownBlock>()) }
    LaunchedEffect(markdown) {
        blocks = withContext(Dispatchers.Default) { MarkdownParser.parse(markdown) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(blocks) { block -> MarkdownBlockView(block) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = rememberInline(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        is MarkdownBlock.Paragraph -> Text(
            text = rememberInline(block.text),
            style = MaterialTheme.typography.bodyMedium,
        )

        is MarkdownBlock.ListItem -> Row(
            modifier = Modifier.padding(start = (block.indent * 16).dp),
        ) {
            Text(
                text = block.marker,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = rememberInline(block.text),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MarkdownBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(
                text = rememberInline(block.text),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MarkdownBlock.CodeBlock -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = block.text,
                style = MonoTextStyle,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }

        is MarkdownBlock.Table -> MarkdownTable(block)

        MarkdownBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    // Column widths are fixed rather than measured: a scanned table can be arbitrarily
    // wide, and horizontal scrolling beats reflowing cells into unreadable slivers.
    val columnWidth = 148.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            table.header.forEach { cell ->
                Text(
                    text = rememberInline(cell),
                    style = TableHeaderStyle,
                    modifier = Modifier
                        .width(columnWidth)
                        .padding(horizontal = 10.dp),
                )
            }
        }

        table.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HorizontalDivider()
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // Ragged rows are common in OCR output; pad short ones so columns align.
                repeat(maxOf(row.size, table.header.size)) { column ->
                    Text(
                        text = rememberInline(row.getOrElse(column) { "" }),
                        style = TableCellStyle,
                        modifier = Modifier
                            .width(columnWidth)
                            .padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

/** Inline emphasis is a pass of regexes per string; done once, not on every recomposition. */
@Composable
private fun rememberInline(text: String): AnnotatedString =
    remember(text) { MarkdownInline.annotate(text) }
