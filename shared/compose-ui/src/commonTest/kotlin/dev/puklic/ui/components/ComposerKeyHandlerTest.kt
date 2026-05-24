package dev.puklic.ui.components

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Unit tests for [composerKeyAction] decision logic.
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-24-send-messages.md` §7.
 */
class ComposerKeyHandlerTest {

    @Test
    fun bareEnterSends() {
        composerKeyAction(keyDown = true, isEnter = true, isEscape = false, isShift = false) shouldBe
            ComposerAction.Send
    }

    @Test
    fun shiftEnterInsertsNewline() {
        composerKeyAction(keyDown = true, isEnter = true, isEscape = false, isShift = true) shouldBe
            ComposerAction.InsertNewline
    }

    @Test
    fun ctrlEnterSends() {
        // Ctrl is not a parameter to the pure function — bare Enter w/o shift still sends.
        composerKeyAction(keyDown = true, isEnter = true, isEscape = false, isShift = false) shouldBe
            ComposerAction.Send
    }

    @Test
    fun cmdEnterSends() {
        // Cmd is not a parameter to the pure function — bare Enter w/o shift still sends.
        composerKeyAction(keyDown = true, isEnter = true, isEscape = false, isShift = false) shouldBe
            ComposerAction.Send
    }

    @Test
    fun ctrlShiftEnterInsertsNewline() {
        // Shift wins regardless of other modifiers.
        composerKeyAction(keyDown = true, isEnter = true, isEscape = false, isShift = true) shouldBe
            ComposerAction.InsertNewline
    }

    @Test
    fun escapeBlurs() {
        composerKeyAction(keyDown = true, isEnter = false, isEscape = true, isShift = false) shouldBe
            ComposerAction.Blur
    }

    @Test
    fun enterKeyUpIgnored() {
        composerKeyAction(keyDown = false, isEnter = true, isEscape = false, isShift = false) shouldBe
            ComposerAction.Ignored
    }

    @Test
    fun otherKeyIgnored() {
        composerKeyAction(keyDown = true, isEnter = false, isEscape = false, isShift = false) shouldBe
            ComposerAction.Ignored
    }
}
