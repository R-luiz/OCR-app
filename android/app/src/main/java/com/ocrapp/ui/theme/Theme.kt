package com.ocrapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF41618E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF555F71),
    secondaryContainer = Color(0xFFD9E3F8),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE0E2EC),
    background = Color(0xFFFDFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF284777),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBDC7DC),
    secondaryContainer = Color(0xFF3D4758),
    surface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFF44474F),
    background = Color(0xFF1B1B21),
)

@Composable
fun OcrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Material You on Android 12+, so the app picks up the user's wallpaper palette.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OcrTypography,
        content = content,
    )
}
