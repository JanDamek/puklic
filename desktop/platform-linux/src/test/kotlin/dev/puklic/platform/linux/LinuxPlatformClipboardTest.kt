package dev.puklic.platform.linux

import dev.puklic.platform.PlatformUnavailable
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxPlatformClipboardTest {

    @Test
    fun `setImage throws PlatformUnavailable in Phase 1`() = runTest {
        val clip = LinuxPlatformClipboard(env = { null }, runner = AlwaysPresent())
        assertFailsWith<PlatformUnavailable> { clip.setImage(ByteArray(0), "image/png") }
    }

    @Test
    fun `throws PlatformUnavailable when no backend available`() = runTest {
        val clip = LinuxPlatformClipboard(env = { null }, runner = AlwaysAbsent())
        assertFailsWith<PlatformUnavailable> { clip.setText("hi") }
    }

    @Test
    fun `text roundtrip via real Wayland or X11 backend if available`() = runTest {
        val real = CommandRunner()
        val hasBackend = real.isOnPath("wl-copy") || real.isOnPath("xclip")
        if (!hasBackend) return@runTest

        val clip = LinuxPlatformClipboard()
        val payload = "puklic-clip-${UUID.randomUUID()}"
        clip.setText(payload)
        clip.getText()?.trimEnd('\n') shouldBe payload
    }

    private class AlwaysPresent : CommandRunner() {
        override fun isOnPath(executable: String): Boolean = true
    }
    private class AlwaysAbsent : CommandRunner() {
        override fun isOnPath(executable: String): Boolean = false
    }
}
