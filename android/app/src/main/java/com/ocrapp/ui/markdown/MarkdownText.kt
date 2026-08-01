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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ocrapp.ui.theme.MonoTextStyle
import com.ocrapp.ui.theme.TableCellStyle
import com.ocrapp.ui.theme.TableHeaderStyle

/** Renders parsed Markdown. Wide tables scroll horizontally instead of squeezing text. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(
        modifier = modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block -> MarkdownBlockView(block) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = MarkdownInline.annotate(block.text),
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
            text = MarkdownInline.annotate(block.text),
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
                text = MarkdownInline.annotate(block.text),
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
                text = MarkdownInline.annotate(block.text),
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
                    text = MarkdownInline.annotate(cell),
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
                val cells = row + List((table.header.size - row.size).coerceAtLeast(0)) { "" }
                cells.forEach { cell ->
                    Text(
                        text = MarkdownInline.annotate(cell),
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
