package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.flow.Flow

/**
 * Encoder facade for screen-share video. The current jvm implementation spawns an ffmpeg
 * subprocess (see `FfmpegVideoEncoder.jvm.kt`) and emits one [EncodedFrame] per H.264 NAL
 * unit it parses from the Annex-B output stream. See
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §6 for the ffmpeg
 * command line.
 *
 * The [encode] flow is **cold**: collecting it spawns the subprocess; closing the collection
 * (cancellation or `awaitClose`) terminates it. [stop] is provided for explicit shutdown
 * from outside the collector.
 */
internal interface VideoEncoder {
    /** Cold flow emitting one frame per H.264 NAL unit (Annex-B-stripped). */
    fun encode(): Flow<EncodedFrame>

    suspend fun stop()
}
