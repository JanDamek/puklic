package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Unit tests for [AppleScriptWindowParser]. Verifies that the parser correctly turns the
 * comma-separated `App|Title` records produced by `osascript` into [ScreenSource.Window]
 * entries, skipping malformed and empty records.
 */
class AppleScriptWindowParserTest {

    @Test
    fun `parses multiple App pipe Title pairs into windows`() {
        val raw = "Safari|Apple, Terminal|zsh, Music|Music"

        val windows = AppleScriptWindowParser.parse(raw)

        windows.shouldHaveSize(EXPECTED_THREE)
        windows.map { (it as ScreenSource.Window).appName to it.displayName }
            .shouldContainExactlyInAnyOrder(
                "Safari" to "Apple",
                "Terminal" to "zsh",
                "Music" to "Music",
            )
    }

    @Test
    fun `skips entries without a pipe separator`() {
        val raw = "Safari|Apple, no-pipe-here, Terminal|zsh"

        val windows = AppleScriptWindowParser.parse(raw)

        windows.shouldHaveSize(EXPECTED_TWO)
    }

    @Test
    fun `skips entries with empty title`() {
        val raw = "Safari|, Terminal|zsh, |Empty App"

        val windows = AppleScriptWindowParser.parse(raw)

        windows.shouldHaveSize(1)
        (windows.first() as ScreenSource.Window).appName shouldBe "Terminal"
    }

    @Test
    fun `assigns unique stable ids prefixed with win`() {
        val raw = "Safari|Apple, Safari|Apple, Terminal|zsh"

        val windows = AppleScriptWindowParser.parse(raw)

        val ids = windows.map { it.id }
        ids.toSet().shouldHaveSize(ids.size) // all unique
        ids.all { it.startsWith("win:") } shouldBe true
    }

    @Test
    fun `empty input returns empty list`() {
        AppleScriptWindowParser.parse("").shouldBeEmpty()
        AppleScriptWindowParser.parse("   \n  ").shouldBeEmpty()
    }

    @Test
    fun `splits naively on comma-space — titles with commas may fragment`() {
        // osascript returns AppleScript list flat-joined with ", " — titles can contain commas.
        // We accept the simple-parser trade-off and just verify the parser produces only
        // well-formed Window entries (non-empty app and title, valid ids).
        val raw = "Terminal|hello, world|fake, Safari|Apple"

        val windows = AppleScriptWindowParser.parse(raw)

        windows.all { (it as ScreenSource.Window).appName.isNotEmpty() } shouldBe true
        windows.all { (it as ScreenSource.Window).displayName.isNotEmpty() } shouldBe true
        windows.all { it.id.startsWith("win:") } shouldBe true
    }

    private companion object {
        const val EXPECTED_TWO = 2
        const val EXPECTED_THREE = 3
    }
}
