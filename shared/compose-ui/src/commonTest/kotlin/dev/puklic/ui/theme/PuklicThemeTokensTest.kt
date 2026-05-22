package dev.puklic.ui.theme

import io.kotest.matchers.shouldBe
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Token-level smoke tests — guard against accidental drift on the placeholder values from
 * `docs/04_ui/design-system.md`. These will be re-tuned when the final accent is chosen.
 */
class PuklicThemeTokensTest {

    @Test
    fun `primary accent matches design-system placeholder`() {
        PuklicDarkColorScheme.primary shouldBe Color(0xFF7C9CFF)
    }

    @Test
    fun `surface background matches design-system placeholder`() {
        PuklicDarkColorScheme.background shouldBe Color(0xFF0F1115)
        PuklicDarkColorScheme.surface shouldBe Color(0xFF16191F)
    }

    @Test
    fun `custom presence colors match design-system table`() {
        PuklicDarkCustomColors.online shouldBe Color(0xFF43B581)
        PuklicDarkCustomColors.idle shouldBe Color(0xFFFAA61A)
        PuklicDarkCustomColors.dnd shouldBe Color(0xFFF04747)
        PuklicDarkCustomColors.offline shouldBe Color(0xFF747F8D)
        PuklicDarkCustomColors.mention shouldBe Color(0xFFFFD66B)
    }

    @Test
    fun `spacing scale matches design-system values`() {
        val s = PuklicSpacing()
        s.space0 shouldBe 0.dp
        s.space3 shouldBe 8.dp
        s.space4 shouldBe 12.dp
        s.space8 shouldBe 48.dp
        s.compactPadding shouldBe 12.dp
    }
}
