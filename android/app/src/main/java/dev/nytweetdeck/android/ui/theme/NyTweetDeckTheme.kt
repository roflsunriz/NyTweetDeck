package dev.nytweetdeck.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import dev.nytweetdeck.android.model.AccentColor
import dev.nytweetdeck.android.model.AppFontSize

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
    accentColor: AccentColor = AccentColor.BLUE,
    fontSize: AppFontSize = AppFontSize.DEFAULT,
    content: @Composable () -> Unit,
) {
    val accent = accentColor.color
    val colors = (if (darkTheme) DarkColors else LightColors).copy(
        primary = accent,
        secondary = accent,
    )
    val density = LocalDensity.current
    val fontScale = when (fontSize) {
        AppFontSize.SMALL -> 0.88f
        AppFontSize.DEFAULT -> 1f
        AppFontSize.LARGE -> 1.18f
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

private val AccentColor.color: Color
    get() = when (this) {
        AccentColor.BLUE -> Color(0xFF2AA9E0)
        AccentColor.PURPLE -> Color(0xFF8B7CF6)
        AccentColor.PINK -> Color(0xFFF05A9D)
        AccentColor.ORANGE -> Color(0xFFF28C38)
        AccentColor.GREEN -> Color(0xFF39B982)
        AccentColor.YELLOW -> Color(0xFFE0B92A)
    }
