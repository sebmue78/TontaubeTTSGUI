package de.tontaube.vorlesen.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val Light = lightColorScheme(
    primary = Color(0xFF3D5A80),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3C),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2C1900),
    tertiary = Color(0xFF406753),
    tertiaryContainer = Color(0xFFC2EDD3),
    background = Color(0xFFF8F6FB),
    surface = Color(0xFFF8F6FB),
    surfaceVariant = Color(0xFFE0E2EC),
    surfaceContainerLow = Color(0xFFF2F0F6),
    surfaceContainer = Color(0xFFECEAF1),
    surfaceContainerHigh = Color(0xFFE6E5EC),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF244775),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFFFB950),
    secondaryContainer = Color(0xFF694300),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFFA6D0B8),
    tertiaryContainer = Color(0xFF284E3C),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF43474E),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Schrift für den Lesetext. */
val ReadingStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 29.sp)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TontaubeTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}

/** Farbe für das Projekt-Cover (stabil je Projekt). */
fun coverColors(seed: Long, dark: Boolean): Pair<Color, Color> {
    val palette = listOf(
        0xFF3D5A80 to 0xFF98C1D9, 0xFF6D597A to 0xFFB56576, 0xFF2A9D8F to 0xFF8AB17D, 0xFFE76F51 to 0xFFF4A261,
        0xFF264653 to 0xFF2A9D8F, 0xFF7B2CBF to 0xFFC77DFF, 0xFF9C6644 to 0xFFDDB892, 0xFF386641 to 0xFFA7C957,
    )
    val (a, b) = palette[(seed % palette.size).toInt().let { if (it < 0) it + palette.size else it }]
    val f = if (dark) 0.85f else 1f
    return Color(a).copy(alpha = f) to Color(b).copy(alpha = f)
}
