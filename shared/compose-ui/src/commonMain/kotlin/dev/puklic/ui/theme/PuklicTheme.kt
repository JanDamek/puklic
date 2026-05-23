package dev.puklic.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme composable. Provides Material 3 dark scheme + Puklic-specific custom tokens
 * (mention colors, presence colors, spacing) via [LocalPuklicColors] and [LocalPuklicSpacing].
 *
 * MVP is dark-only per `docs/04_ui/design-system.md` (light theme is Phase 2).
 *
 * Note: we explicitly override `LocalContentColor` to `onSurface`. Material 3 only sets
 * `LocalContentColor` inside `Surface { ... }` containers; our top-level screens use raw
 * `Modifier.background(...)` boxes, so without this override `Text(...)` calls without an
 * explicit `color` parameter inherit Compose's default `Color.Black`, which is invisible on
 * the dark theme. This is the central fix for the "dark text on dark background" bug.
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
            LocalContentColor provides PuklicDarkColorScheme.onSurface,
            content = content,
        )
    }
}
