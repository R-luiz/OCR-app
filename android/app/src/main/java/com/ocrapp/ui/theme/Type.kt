package com.ocrapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val OcrTypography = Typography()

/** Used for raw Markdown and recognized-text editing, where alignment carries meaning. */
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

val TableCellStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

val TableHeaderStyle = TableCellStyle.copy(fontWeight = FontWeight.SemiBold)
