package dev.puklic.platform.macos

import dev.puklic.platform.PlatformUnavailable
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MacOsPlatformClipboardTest {

    @Test
    fun `setText throws when pbcopy missing`() = runTest {
        val clip = MacOsPlatformClipboard(runner = AbsentRunner())
        assertFailsWith<PlatformUnavailable> { clip.setText("x") }
    }

    @Test
    fun `roundtrip text via pbcopy and pbpaste when available`() = runTest {
        val real = CommandRunner()
        if (!real.isOnPath(MacOsPlatformClipboard.PBCOPY) || !real.isOnPath(MacOsPlatformClipboard.PBPASTE)) {
            return@runTest
        }
        val clip = MacOsPlatformClipboard()
        val payload = "puklic-clip-${UUID.randomUUID()}"
        clip.setText(payload)
        // pbpaste does not append newline; compare exact.
        clip.getText() shouldBe payload
    }

    private class AbsentRunner : CommandRunner() {
        override fun isOnPath(executable: String): Boolean = false
    }
}
