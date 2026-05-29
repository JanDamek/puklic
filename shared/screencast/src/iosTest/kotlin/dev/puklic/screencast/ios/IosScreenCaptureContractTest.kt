/*
 * Compile-only contract test for the FP-12 iOS ScreenCapture surface.
 *
 * The real pipeline runs only inside the host app where the Broadcast Extension is
 * sandboxed-mounted alongside the main process via App Group container — out of reach for
 * the headless iosTest runner. The test below locks the public types and KMP interface
 * conformance so any drift surfaces at `compileTestKotlinIos*` immediately.
 *
 * Reference:
 *   docs/03_infrastructure/architect-reports/2026-05-29-fp12-ios-screencast-impl.md §8
 */

package dev.puklic.screencast.ios

import dev.puklic.screencast.ScreenCapture
import dev.puklic.screencast.ScreenCaptureFactory
import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.EncodedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class IosScreenCaptureContractTest {

    @Test
    fun enumeratorReturnsSingleSyntheticSource() = runTest {
        val enum: ScreenSourceEnumerator = IosScreenSourceEnumerator
        val sources = enum.list()
        assertEquals(1, sources.size)
        val source = sources.first()
        assertTrue(source is ScreenSource.Monitor)
        assertEquals(IosScreenSourceEnumerator.ID, source.id)
    }

    @Test
    fun factoryImplementsScreenCaptureFactory() {
        val factory: ScreenCaptureFactory = IosScreenCaptureFactory
        // Compile-time: must accept the canonical signature.
        @Suppress("UNUSED_VARIABLE")
        val ref: (ScreenSource, Boolean, () -> H264Encoder) -> ScreenCapture =
            factory::create
        assertNotNull(ref)
    }

    @Test
    fun factoryRejectsForeignScreenSource() {
        val foreign = ScreenSource.Monitor(id = "not-ios", displayName = "x", widthPx = 1, heightPx = 1)
        assertFailsWith<IllegalArgumentException> {
            IosScreenCaptureFactory.create(foreign, shareAudio = false) { NoopH264Encoder }
        }
    }

    @Test
    fun bgraToYuv420SizesAreConsistent() {
        val conv = BgraToYuv420(width = 1280, height = 720)
        assertEquals(1280 * 720 * 3 / 2, conv.yuvSize)
        val src = ByteArray(1280 * 720 * 4) // opaque black BGRA
        val dst = ByteArray(conv.yuvSize)
        conv.convert(src, sourceBytesPerRow = 1280 * 4, dst = dst)
        // BT.601 limited range black: Y == 16, U == V == 128.
        assertEquals(16.toByte(), dst[0])
        val uOff = 1280 * 720
        val vOff = uOff + (1280 * 720) / 4
        assertEquals(128.toByte(), dst[uOff])
        assertEquals(128.toByte(), dst[vOff])
    }

    @Test
    fun bgraToYuv420RejectsOddDimensions() {
        assertFailsWith<IllegalArgumentException> { BgraToYuv420(width = 1281, height = 720) }
        assertFailsWith<IllegalArgumentException> { BgraToYuv420(width = 1280, height = 721) }
    }

    @Test
    fun screenCaptureExposesAudioOnlyWhenRequested() {
        val withoutAudio: ScreenCapture = IosScreenCapture(
            shareAudio = false,
            h264EncoderFactory = { NoopH264Encoder },
        )
        assertNull(withoutAudio.audio)
        withoutAudio.close()

        val withAudio: ScreenCapture = IosScreenCapture(
            shareAudio = true,
            h264EncoderFactory = { NoopH264Encoder },
        )
        assertNotNull(withAudio.audio)
        withAudio.close()
    }

    private object NoopH264Encoder : H264Encoder {
        override fun encode(yuv420p: ByteArray): EncodedFrame? = null
        override fun close() {}
    }
}
