package com.yg.minivault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 主题模式，设置页可选。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 文字大小档位（相对系统字号再乘一次）。 */
val FontScaleOptions: List<Pair<Float, String>> = listOf(
    0.9f to "小",
    1.0f to "标准",
    1.15f to "大",
    1.3f to "特大",
)

/** 供玻璃面板判断明暗，避免每个 Composable 都去算一遍亮度。 */
val LocalGlassDark = staticCompositionLocalOf { true }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF1E2C46),
    onPrimaryContainer = Color(0xFFE1ECFF),
    secondary = Color(0xFF58D6A6),
    onSecondary = Color(0xFF04241A),
    background = Color(0xFF07080B),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF15171D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E212A),
    onSurfaceVariant = Color(0xFFE2E7EF),
    outline = Color(0xFF4C5464),
    error = Color(0xFFFF8A80),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0x2E0A84FF),
    onPrimaryContainer = Color(0xFF00243A),
    secondary = Color(0xFF0F9D58),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEDF2FB),
    onBackground = Color(0xFF0E1526),
    surface = Color(0x99FFFFFF),
    onSurface = Color(0xFF0E1526),
    surfaceVariant = Color(0x8CFFFFFF),
    onSurfaceVariant = Color(0x9E0E1526),
    outline = Color(0x1F0E1526),
    error = Color(0xFFD93025),
)

@Composable
fun MiniVaultTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val base = LocalDensity.current
    val scaled = base.fontScale * fontScale.coerceIn(0.8f, 1.6f)
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, scaled),
        LocalGlassDark provides dark,
    ) {
        val scheme = if (dark) DarkScheme else LightScheme
        MaterialTheme(colorScheme = scheme) {
            // 透明 Scaffold 的 contentColorFor() 会返回 Unspecified，
            // 于是没写 color 的 Text 会退化成纯黑 —— 深色下就是「看不见的字」。
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
