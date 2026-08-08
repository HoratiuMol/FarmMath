package com.farmmathbuilder.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.farmmathbuilder.app.domain.TextSizeOption

private val LightColors = lightColorScheme(
    primary = GrassGreenDark,
    secondary = WheatGold,
    tertiary = SkyBlue,
    background = CardCream,
    surface = CardCream
)

private val DarkColors = darkColorScheme(
    primary = GrassGreen,
    secondary = WheatGold,
    tertiary = SkyBlue
)

private val HighContrastColors = darkColorScheme(
    primary = HighContrastAccent,
    secondary = HighContrastAccent,
    background = HighContrastBackground,
    surface = HighContrastBackground,
    onBackground = HighContrastForeground,
    onSurface = HighContrastForeground
)

fun typographyForScale(scale: Float): Typography {
    fun sz(base: Int) = (base * scale).sp
    return Typography(
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = sz(16)),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = sz(14)),
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = sz(22)),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = sz(18)),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = sz(14))
    )
}

@Composable
fun FarmMathBuilderTheme(
    highContrast: Boolean = false,
    textSize: TextSizeOption = TextSizeOption.MEDIUM,
    content: @Composable () -> Unit
) {
    val colors = when {
        highContrast -> HighContrastColors
        isSystemInDarkTheme() -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = typographyForScale(textSize.scale),
        content = content
    )
}
