package dev.puklic.ui.theme

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * RED tests for the presence color tokens introduced by issue #8 (architect report v2 §7).
 * The dark theme palette must expose four distinct, semantically named presence colors:
 * presenceOnline, presenceIdle, presenceDnd, presenceOffline.
 *
 * Existing `PuklicCustomColors` uses short names (`online`, `idle`, `dnd`, `offline`); per
 * architect spec these are renamed to the `presence*` prefix so the call sites in
 * `PresenceState.toDotColor()` read as a stable theme contract. Until impl renames the
 * tokens, these tests fail with unresolved-reference.
 */
class PuklicColorSchemePresenceTest {

    @Test
    fun dark_palette_provides_four_distinct_presence_colors() {
        val dark = PuklicDarkCustomColors
        val colors = setOf(
            dark.presenceOnline,
            dark.presenceIdle,
            dark.presenceDnd,
            dark.presenceOffline,
        )
        colors.size shouldBe 4
    }

    @Test
    fun presence_online_is_not_offline() {
        val dark = PuklicDarkCustomColors
        (dark.presenceOnline == dark.presenceOffline) shouldBe false
    }
}
