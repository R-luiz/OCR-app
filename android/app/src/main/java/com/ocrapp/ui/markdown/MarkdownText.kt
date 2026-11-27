package com.ocrapp.ui.markdown

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ocrapp.ui.theme.MonoTextStyle
import com.ocrapp.ui.theme.TableCellStyle
import com.ocrapp.ui.theme.TableHeaderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Half the space between two blocks: each item pads itself by this much above and below. */
private val HalfGap = 4.dp

/**
 * Column widths are fixed rather than measured: a scanned table can be arbitrarily wide,
 * and horizontal scrolling beats reflowing cells into unreadable slivers. Fixed widths
 * also keep the columns aligned across rows that are laid out as separate list items.
 */
private val TableColumnWidth = 148.dp

/**
 * Renders Markdown as a scrolling list of blocks.
 *
 * A parsed document can run to thousands of blocks, so it is parsed and its inline
 * emphasis applied off the main thread, and only the items on screen are composed: a
 * table is one item per row (see [MarkdownItem]). Wide tables scroll horizontally, all
 * their rows together, instead of squeezing their text.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var rendered by remember(markdown) { mutableStateOf(emptyList<MarkdownItem>()) }
    LaunchedEffect(markdown) {
        rendered = withContext(Dispatchers.Default) { MarkdownItems.build(MarkdownParser.parse(markdown)) }
    }
    // Tables are numbered from 0 in order, and each starts with its header row.
    val tableScrolls = remember(rendered) {
        List(rendered.count { it is MarkdownItem.TableRow && it.isHeader }) { ScrollState(0) }
    }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        items(rendered, contentType = { it::class }) { item -> MarkdownItemView(item, tableScrolls) }
    }
}

@Composable
private fun MarkdownItemView(item: MarkdownItem, tableScrolls: List<ScrollState>) {
    when (item) {
        is MarkdownItem.Heading -> Text(
            text = item.text,
            style = when (item.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = HalfGap + 8.dp, bottom = HalfGap),
        )

        is MarkdownItem.Paragraph -> Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = HalfGap),
        )

        is MarkdownItem.ListItem -> Row(
            modifier = Modifier.padding(start = (item.indent * 16).dp, top = HalfGap, bottom = HalfGap),
        ) {
            Text(
                text = item.marker,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MarkdownItem.Quote -> Row(
            modifier = Modifier
                .padding(vertical = HalfGap)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MarkdownItem.Code -> Column(
            modifier = Modifier
                .padding(vertical = HalfGap)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = item.text,
                style = MonoTextStyle,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }

        is MarkdownItem.TableRow -> MarkdownTableRow(item, tableScrolls[item.table])

        MarkdownItem.Divider -> HorizontalDivider(Modifier.padding(vertical = HalfGap + 4.dp))
    }
}

/**
 * One row of a table. The header rounds the table's top corners and the last row its
 * bottom ones; [scroll] is the table's, shared by all its rows so they scroll as one.
 */
@Composable
private fun MarkdownTableRow(row: MarkdownItem.TableRow, scroll: ScrollState) {
    val top = if (row.isHeader) 8.dp else 0.dp
    val bottom = if (row.isLast) 8.dp else 0.dp
    val background = if (row.isHeader) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Column(
        modifier = Modifier
            .padding(top = if (row.isHeader) HalfGap else 0.dp, bottom = if (row.isLast) HalfGap else 0.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(background),
    ) {
        if (row.hasDivider) HorizontalDivider()
        Row(
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            row.cells.forEach { cell ->
                Text(
                    text = cell,
                    style = if (row.isHeader) TableHeaderStyle else TableCellStyle,
                    modifier = Modifier
                        .width(TableColumnWidth)
                        .padding(horizontal = 10.dp),
                )
            }
        }
    }
}
