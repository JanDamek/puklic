package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * Slice 4 — real enumeration smoke test, gated on the host having ffmpeg + being macOS.
 *
 * Skipped silently on non-macOS hosts or when ffmpeg is not installed; on a macOS dev box
 * with `brew install ffmpeg`, this should report at least one [ScreenSource.Monitor].
 */
class MacScreenSourceEnumeratorTest {

    @Test
    fun `real ffmpeg enumeration returns at least one monitor on macOS`() = runTest {
        val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        if (!isMac) return@runTest

        val ffmpegCandidates = listOf(
            System.getenv("PUKLIC_FFMPEG"),
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg",
        ).filterNotNull().firstOrNull { File(it).canExecute() } ?: return@runTest

        val sources = MacScreenSourceEnumerator(ffmpegPath = ffmpegCandidates).list()

        sources.shouldNotBeEmpty()
        sources.first().shouldBeInstanceOf<ScreenSource.Monitor>()
    }
}
