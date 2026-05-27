package dev.puklic.voice.screenshare.encoder

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFails

/**
 * Pure-logic tests for [resolveInputForOs] — the (os, source-id) → (libavdevice format, URL)
 * mapping used by [LibavVideoEncoder] for screen capture input selection.
 *
 * This covers the Wayland/PipeWire capture path (Phase 4.1 roadmap item: "PipeWire stream
 * capture"). The actual native libavdevice open / decode / encode loop is exercised by
 * [LibavVideoEncoderTest] using the lavfi testsrc input (host-agnostic). Verifying the real
 * `pipewire` demuxer end-to-end requires a Linux+Wayland compositor with xdg-desktop-portal
 * and an interactive user picker dialog; see `docs/05_platforms/linux-wayland.md` for the
 * manual smoke procedure.
 */
class ResolveInputForOsTest {

    @Test
    fun `linux maps to pipewire input format with node id as url`() {
        resolveInputForOs("Linux", "42") shouldBe ("pipewire" to "42")
    }

    @Test
    fun `linux detection is case-insensitive`() {
        resolveInputForOs("GNU/linux", "7") shouldBe ("pipewire" to "7")
    }

    @Test
    fun `mac maps to avfoundation with none-suffixed audio slot`() {
        resolveInputForOs("Mac OS X", "0") shouldBe ("avfoundation" to "0:none")
    }

    @Test
    fun `unsupported os fails fast`() {
        assertFails { resolveInputForOs("Windows 11", "0") }
        assertFails { resolveInputForOs("", "0") }
    }
}
