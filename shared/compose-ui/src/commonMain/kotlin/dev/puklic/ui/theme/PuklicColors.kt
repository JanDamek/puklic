package dev.puklic.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 dark color scheme for Puklic, using placeholder accent values from
 * `docs/04_ui/design-system.md`. Final accent tuning happens during implementation.
 */
public val PuklicDarkColorScheme: androidx.compose.material3.ColorScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0A1226),
    primaryContainer = Color(0xFF2C3E66),
    onPrimaryContainer = Color(0xFFE6EAF2),
    secondary = Color(0xFF9BB0CC),
    onSecondary = Color(0xFF0A1226),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF16191F),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E232B),
    onSurfaceVariant = Color(0xFFA8B0BD),
    surfaceContainer = Color(0xFF1A1E25),
    outline = Color(0xFF2A3038),
    outlineVariant = Color(0xFF1E232B),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0000),
)

/**
 * Tokens that don't fit the Material 3 color scheme (mentions, presence colors).
 * Exposed via [LocalPuklicColors] CompositionLocal.
 */
@Immutable
public data class PuklicCustomColors(
    val mention: Color,
    val mentionBackground: Color,
    val online: Color,
    val idle: Color,
    val dnd: Color,
    val offline: Color,
)

public val PuklicDarkCustomColors: PuklicCustomColors = PuklicCustomColors(
    mention = Color(0xFFFFD66B),
    mentionBackground = Color(0xFF3D2E0A),
    online = Color(0xFF43B581),
    idle = Color(0xFFFAA61A),
    dnd = Color(0xFFF04747),
    offline = Color(0xFF747F8D),
)

public val LocalPuklicColors: androidx.compose.runtime.ProvidableCompositionLocal<PuklicCustomColors> =
    staticCompositionLocalOf { PuklicDarkCustomColors }
