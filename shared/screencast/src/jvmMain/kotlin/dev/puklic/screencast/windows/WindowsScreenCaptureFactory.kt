package dev.puklic.screencast.windows

import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.screencast.ScreenCaptureFactory
import dev.puklic.voice.screenshare.ScreenSource

/**
 * Windows JVM entry-point implementing [ScreenCaptureFactory]. Construction is free —
 * actual COM initialisation + DXGI duplication setup happens lazily when the consumer
 * collects [ScreenCapture.frames].
 *
 * Wired into `:desktop:app/DependencyGraph.kt` by FP-10; not auto-discovered here because
 * the screencast module deliberately stays platform-detection-free (the Linux factory
 * follows the same pattern).
 */
public object WindowsScreenCaptureFactory : ScreenCaptureFactory {
    override fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture = WindowsScreenCapture(
        source = source,
        shareAudio = shareAudio,
        h264EncoderFactory = h264EncoderFactory,
    )
}
