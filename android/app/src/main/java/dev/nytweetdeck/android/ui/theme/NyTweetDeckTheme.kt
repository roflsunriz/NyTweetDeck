package dev.nytweetdeck.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2AA9E0),
    onPrimary = Color(0xFF071319),
    primaryContainer = Color(0xFF123A4C),
    onPrimaryContainer = Color(0xFFBCE9FC),
    background = Color(0xFF101820),
    onBackground = Color(0xFFF2F6F8),
    surface = Color(0xFF17232E),
    onSurface = Color(0xFFF2F6F8),
    surfaceVariant = Color(0xFF1D2B37),
    onSurfaceVariant = Color(0xFF9DB0BD),
    outline = Color(0xFF334653),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF168FC5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F1FC),
    onPrimaryContainer = Color(0xFF052F42),
    background = Color(0xFFEDF2F5),
    onBackground = Color(0xFF16242D),
    surface = Color.White,
    onSurface = Color(0xFF16242D),
    surfaceVariant = Color(0xFFF8FAFB),
    onSurfaceVariant = Color(0xFF607682),
    outline = Color(0xFFD0DCE3),
    error = Color(0xFFD64040),
)

@Composable
fun NyTweetDeckTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
