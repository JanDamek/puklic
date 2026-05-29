package dev.puklic.screencast.macos

import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.screencast.ScreenCaptureFactory
import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource

/**
 * `ScreenCaptureFactory` implementation that wires the ScreenCaptureKit
 * bridge (FP-8) to the KMP `ScreenCapture` contract. Selected from
 * `DefaultScreenShareClient` when `os.name = mac` — that selection lives in a
 * later slice (issue #48 explicitly defers integration).
 *
 * The default [defaultH264EncoderFactory] returns a fresh
 * [LibavPushH264Encoder] sized at 1920×1080, 2 500 kb/s, 30 fps — same preset
 * as the existing source-driven `LibavVideoEncoder`. Callers that want a
 * different resolution / bitrate pass their own [H264EncoderFactory] through
 * the [ScreenCaptureFactory] contract.
 */
public object MacScreenCaptureFactory : ScreenCaptureFactory {

    override fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture = MacScreenCapture(
        source = source,
        shareAudio = shareAudio,
        encoderFactory = h264EncoderFactory,
    )

    /**
     * Convenience constructor with the default Puklic encoder preset baked
     * in. Useful for callers that do not need to override the encoder.
     */
    public fun defaultH264EncoderFactory(): H264EncoderFactory = {
        LibavPushH264Encoder(
            width = MacScreenCapture.DEFAULT_WIDTH,
            height = MacScreenCapture.DEFAULT_HEIGHT,
            bitrateKbps = DEFAULT_BITRATE_KBPS,
            framerate = MacScreenCapture.DEFAULT_FRAMERATE,
        )
    }

    public const val DEFAULT_BITRATE_KBPS: Int = 2_500
}

/** Type alias for clarity at the factory call site. */
internal typealias MacEncoder = H264Encoder
