package dev.puklic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme composable. Provides Material 3 dark scheme + Puklic-specific custom tokens
 * (mention colors, presence colors, spacing) via [LocalPuklicColors] and [LocalPuklicSpacing].
 *
 * MVP is dark-only per `docs/04_ui/design-system.md` (light theme is Phase 2).
 */
@Composable
public fun PuklicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PuklicDarkColorScheme,
        typography = PuklicTypography,
        shapes = PuklicShapes,
    ) {
        CompositionLocalProvider(
            LocalPuklicColors provides PuklicDarkCustomColors,
            LocalPuklicSpacing provides PuklicSpacing(),
            content = content,
        )
    }
}
