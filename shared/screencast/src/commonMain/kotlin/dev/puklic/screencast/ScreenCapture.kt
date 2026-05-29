package dev.puklic.screencast

import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.flow.Flow

/**
 * KMP-wide public contract for one ongoing screen capture session.
 *
 * Implementations:
 *   - Linux JVM — `LinuxScreenCapture` wrapping `LibavVideoEncoder` +
 *     `PipeWireAudioReader` (FP-7, this slice). The Linux adapter is trivial
 *     because `LibavVideoEncoder.encode()` already returns a
 *     `Flow<EncodedFrame>` with the exact shape required by [frames].
 *   - macOS JVM — `MacScreenCapture` via `ScreenCaptureKit` over JNA (FP-8).
 *   - Windows JVM — `WindowsScreenCapture` via `IDXGIOutputDuplication` over
 *     JNA (FP-9).
 *   - iOS iosMain — `IosScreenCapture` consuming an `RPBroadcastSampleHandler`
 *     extension feed through App Group shared memory; the captured CVPixelBuffer
 *     stream is encoded by `IosH264Encoder` (VideoToolbox, already in
 *     :shared:voice-codec) inside the loop the implementation owns (FP-12).
 *
 * The `frames` flow shape is the same as
 * [dev.puklic.voice.screenshare.encoder.VideoEncoder.encode] — implementations on
 * push-frame platforms (iOS / macOS / Windows) wrap their capture loop into a
 * cold [Flow] so the consumer-facing contract stays uniform.
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-29-fp7-screencast-extraction.md`
 * and `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` §3.3.
 */
public interface ScreenCapture : AutoCloseable {

    /**
     * Cold flow of encoded video frames. Each [EncodedFrame] is one H.264 NAL
     * unit (Annex-B-stripped) for `videoCodec=H264`, or one IVF-less VP8 raw
     * frame for `videoCodec=VP8`. RTP timestamp + keyframe flag are set by the
     * encoder.
     *
     * Collecting starts capture; cancelling the collection / closing this
     * [ScreenCapture] tears it down.
     */
    public val frames: Flow<EncodedFrame>

    /**
     * Cold flow of 20 ms / 48 kHz / interleaved-stereo / S16 PCM frames when
     * the capture was started with `shareAudio = true` AND the source provides
     * an audio sub-stream (e.g. PipeWire portal audio node id on Linux,
     * ReplayKit `audioApp` sample handler on iOS, ScreenCaptureKit system
     * audio on macOS). `null` when audio capture is not active.
     *
     * Consumers wire this into the soundshare-SSRC Opus encoder + RTP sender
     * (see `SoundshareAudioRtpSender`).
     */
    public val audio: Flow<ShortArray>?
}

/**
 * Factory injected into platform consumers so the H.264 encoder can be
 * substituted per platform (libavcodec on JVM, VideoToolbox on iOS / macOS,
 * Media Foundation on Windows). The factory is invoked once per
 * [ScreenCapture] lifecycle.
 *
 * On Linux the JVM actual ignores this factory because libavcodec demuxer +
 * encoder are fused inside [`LibavVideoEncoder`][dev.puklic.voice.screenshare.encoder.VideoEncoder]
 * — there is no separate "raw frame" stage to feed [H264Encoder]. Other
 * platforms (FP-8/9/12) construct an [H264Encoder] from this factory and feed
 * platform-captured raw frames into it.
 */
public typealias H264EncoderFactory = () -> H264Encoder

/**
 * Platform entry point — produces a configured [ScreenCapture] for the chosen
 * source. Implementations register themselves through whatever platform
 * mechanism is most natural (KMP `expect object` is intentionally not used
 * here; see the architect report §4 for the rationale).
 *
 * @param source the picked [ScreenSource] (returned by [ScreenSourceEnumerator]).
 * @param shareAudio whether the capture should also produce an audio flow.
 * @param h264EncoderFactory how to construct the H.264 encoder for platforms
 *        that decouple capture from encode.
 */
public interface ScreenCaptureFactory {
    public fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture
}
