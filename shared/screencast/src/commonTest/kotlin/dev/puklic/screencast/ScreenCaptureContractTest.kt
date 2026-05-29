package dev.puklic.screencast

import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.EncodedFrame
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Compile-only contract test for the FP-7 :shared:screencast public surface.
 *
 * Locks in the shape of [ScreenCapture] / [ScreenSourceEnumerator] /
 * [ScreenCaptureFactory] / [H264EncoderFactory] so an accidental signature
 * change (return type, suspend modifier, parameter order) breaks compilation
 * here before it breaks every downstream platform actual in FP-8 / FP-9 /
 * FP-12.
 */
class ScreenCaptureContractTest {

    @Test
    fun screenCaptureExposesFramesAndOptionalAudio() {
        val capture: ScreenCapture = FakeScreenCapture(audioEnabled = false)
        capture.frames.shouldBeInstanceOf<Flow<EncodedFrame>>()
        capture.audio shouldBe null
        capture.close()

        val withAudio: ScreenCapture = FakeScreenCapture(audioEnabled = true)
        withAudio.audio.shouldBeInstanceOf<Flow<ShortArray>>()
        withAudio.close()
    }

    @Test
    fun screenSourceEnumeratorReturnsList() = runTest {
        val enum: ScreenSourceEnumerator = FakeEnumerator
        enum.list() shouldBe listOf(SAMPLE_SOURCE)
    }

    @Test
    fun screenCaptureFactoryProducesScreenCapture() {
        val factory: ScreenCaptureFactory = FakeFactory
        val capture = factory.create(SAMPLE_SOURCE, shareAudio = true) { FakeH264Encoder }
        capture.audio.shouldBeInstanceOf<Flow<ShortArray>>()
        capture.close()
    }

    private object FakeEnumerator : ScreenSourceEnumerator {
        override suspend fun list(): List<ScreenSource> = listOf(SAMPLE_SOURCE)
    }

    private object FakeFactory : ScreenCaptureFactory {
        override fun create(
            source: ScreenSource,
            shareAudio: Boolean,
            h264EncoderFactory: H264EncoderFactory,
        ): ScreenCapture {
            // Pull the encoder factory to lock the typealias signature.
            h264EncoderFactory()
            return FakeScreenCapture(audioEnabled = shareAudio)
        }
    }

    private class FakeScreenCapture(audioEnabled: Boolean) : ScreenCapture {
        override val frames: Flow<EncodedFrame> = emptyFlow()
        override val audio: Flow<ShortArray>? = if (audioEnabled) flowOf(ShortArray(0)) else null
        override fun close() {}
    }

    private object FakeH264Encoder : H264Encoder {
        override fun encode(yuv420p: ByteArray): EncodedFrame? = null
        override fun close() {}
    }

    private companion object {
        val SAMPLE_SOURCE: ScreenSource = ScreenSource.Monitor(
            id = "fake-0",
            displayName = "Fake Monitor",
            widthPx = 1920,
            heightPx = 1080,
        )
    }
}
