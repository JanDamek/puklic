package dev.puklic.ui.screens.main

/**
 * Adaptive window-width classes per `docs/04_ui/adaptive-layouts.md` (Material 3 breakpoints).
 *
 *  - [COMPACT]  `< 600 dp` — phone portrait. Single full-screen message pane; guild rail + channel
 *               list live in a modal drawer (Discord-style, issue #95 Option A).
 *  - [MEDIUM]   `600..839 dp` — phone landscape / small split view. Guild rail always visible;
 *               channel list in a modal drawer over the message pane.
 *  - [EXPANDED] `>= 840 dp` — tablet / desktop. The classic three-pane Row, unchanged.
 */
public enum class WindowLayoutMode { COMPACT, MEDIUM, EXPANDED }

/** Width breakpoint between [WindowLayoutMode.COMPACT] and [WindowLayoutMode.MEDIUM], in dp. */
public const val WINDOW_MEDIUM_MIN_DP: Int = 600

/** Width breakpoint between [WindowLayoutMode.MEDIUM] and [WindowLayoutMode.EXPANDED], in dp. */
public const val WINDOW_EXPANDED_MIN_DP: Int = 840

/**
 * Maps an available width (in dp) to its [WindowLayoutMode]. Pure function — no Compose deps — so it
 * is unit-testable on the JVM without a UI host.
 */
public fun windowLayoutModeFor(widthDp: Int): WindowLayoutMode =
    when {
        widthDp < WINDOW_MEDIUM_MIN_DP -> WindowLayoutMode.COMPACT
        widthDp < WINDOW_EXPANDED_MIN_DP -> WindowLayoutMode.MEDIUM
        else -> WindowLayoutMode.EXPANDED
    }
