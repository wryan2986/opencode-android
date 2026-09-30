package dev.ryan.opencode.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF04212E),
    primaryContainer = Color(0xFF12384A),
    onPrimaryContainer = Color(0xFFCDEBFA),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1B1039),
    secondaryContainer = Color(0xFF32215C),
    onSecondaryContainer = Color(0xFFE6DDFF),
    tertiary = Color(0xFFF472B6),
    onTertiary = Color(0xFF3B0A22),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF0B0B0F),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFFA8A8B3),
    surfaceContainer = Color(0xFF15151A),
    surfaceContainerHigh = Color(0xFF1D1D24),
    surfaceContainerHighest = Color(0xFF26262E),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF26262E),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBFA),
    onPrimaryContainer = Color(0xFF04212E),
    secondary = Color(0xFF6D28D9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DDFF),
    onSecondaryContainer = Color(0xFF1B1039),
    tertiary = Color(0xFFBE185D),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF15151A),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF15151A),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF55555F),
)

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

/** Monospace style shared by the terminal and inline code. */
val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
val MonoTiny = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)

@Composable
fun OpencodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
