package batari.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import batari.app.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF0E6B56), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE1), onPrimaryContainer = Color(0xFF06261E),
    secondary = Color(0xFF3F6375), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E7EF), onSecondaryContainer = Color(0xFF10232C),
    tertiaryContainer = Color(0xFFFFE7B3), onTertiaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFF5F7F6), onBackground = Color(0xFF171C1A),
    surface = Color.White, onSurface = Color(0xFF171C1A),
    surfaceVariant = Color(0xFFE9EEEC), onSurfaceVariant = Color(0xFF49524E),
    outline = Color(0xFF7A8581), outlineVariant = Color(0xFFD2D9D6),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFBDAD7), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD6BC), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0B4F40), onPrimaryContainer = Color(0xFFCDEBE1),
    secondary = Color(0xFFA9CBDD), onSecondary = Color(0xFF0E3443),
    secondaryContainer = Color(0xFF274A5A), onSecondaryContainer = Color(0xFFD9E7EF),
    tertiaryContainer = Color(0xFF574200), onTertiaryContainer = Color(0xFFFFE7B3),
    background = Color(0xFF101413), onBackground = Color(0xFFE2E7E4),
    surface = Color(0xFF1A201E), onSurface = Color(0xFFE2E7E4),
    surfaceVariant = Color(0xFF262E2B), onSurfaceVariant = Color(0xFFBEC8C3),
    outline = Color(0xFF8A9590), outlineVariant = Color(0xFF3A4440),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFBDAD7),
)

private val Type = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontSize = 16.sp),
        displayLarge = TextStyle(fontSize = 84.sp, lineHeight = 92.sp),
    )
}

@Composable
fun BatariTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type) {
        // الواجهة عربية دائمًا، حتى لو كانت لغة الهاتف غير العربية.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
