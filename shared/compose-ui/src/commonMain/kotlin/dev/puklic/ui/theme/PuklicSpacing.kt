package dev.puklic.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens per `docs/04_ui/design-system.md` §"Spacing scale".
 * Values are deliberately small (compact density) — MVP default.
 */
@Immutable
public data class PuklicSpacing(
    val space0: Dp = 0.dp,
    val space1: Dp = 2.dp,
    val space2: Dp = 4.dp,
    val space3: Dp = 8.dp,
    val space4: Dp = 12.dp,
    val space5: Dp = 16.dp,
    val space6: Dp = 24.dp,
    val space7: Dp = 32.dp,
    val space8: Dp = 48.dp,
) {
    /** Default inner panel padding. */
    public val compactPadding: Dp get() = space4

    /** Default message row vertical gap. */
    public val messageGap: Dp get() = space3
}

public val LocalPuklicSpacing: androidx.compose.runtime.ProvidableCompositionLocal<PuklicSpacing> =
    staticCompositionLocalOf { PuklicSpacing() }
