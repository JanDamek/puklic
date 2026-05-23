package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.transport.EncodedFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Spawns ffmpeg to capture a [ScreenSource] and read its H.264 Annex-B output from stdout.
 *
 * Implementation per `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §6:
 * macOS uses `-f avfoundation` with libx264 baseline + `keyint=60:repeat-headers=1`. Linux
 * support lands in Phase 4.1 (PipeWire portal). The ffmpeg binary is located via the
 * `PUKLIC_FFMPEG` environment variable, then a list of well-known macOS paths
 * (`/opt/homebrew/bin/ffmpeg`, `/usr/local/bin/ffmpeg`, `/usr/bin/ffmpeg`), then the `PATH`
 * fallback.
 *
 * Output framing: one [EncodedFrame] per NAL (parsed by [AnnexBStreamReader]). H.264
 * timestamp clock is 90 kHz; at 30 fps each VCL NAL increments the timestamp by 3000.
 * Non-VCL NALs (SPS=7, PPS=8, SEI=6, AUD=9) share the timestamp of the next VCL NAL
 * (which mirrors how RFC 6184 senders pack access units).
 */
internal class FfmpegVideoEncoder(
    private val source: ScreenSource,
    private val shareAudio: Boolean,
    private val framerate: Int = DEFAULT_FRAMERATE,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val ffmpegPath: String = locateFfmpeg(),
    /**
     * Test seam — overrides the input portion of the ffmpeg command. When `null`, a
     * platform-appropriate capture input is synthesized from [source] and [shareAudio].
     * Tests pass e.g. `-f lavfi -i testsrc=...` here to avoid touching real hardware.
     */
    private val inputArgsOverride: List<String>? = null,
) : VideoEncoder {

    private val processRef = AtomicReference<Process?>(null)

    override fun encode(): Flow<EncodedFrame> = channelFlow {
        val args = buildCommand(ffmpegPath, source, shareAudio, framerate, bitrate, inputArgsOverride)
        val proc = ProcessBuilder(args)
            .redirectErrorStream(false)
            .start()
        processRef.set(proc)

        // Drain stderr so the pipe doesn't fill.
        launch(Dispatchers.IO) {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (line.contains("error", ignoreCase = true) ||
                        line.contains("fatal", ignoreCase = true)
                    ) {
                        // TODO(phase-4.1): route via Kermit logger.
                        @Suppress("ForbiddenComment")
                        println("ffmpeg: $line")
                    }
                }
            }
        }

        var pts = 0
        val tsIncrement = RTP_VIDEO_CLOCK_HZ / framerate

        launch(Dispatchers.IO) {
            try {
                AnnexBStreamReader(proc.inputStream).asFlow().collect { nal ->
                    if (nal.isEmpty()) return@collect
                    val nalType = nal[0].toInt() and NAL_TYPE_MASK
                    val keyframe = nalType == NAL_TYPE_IDR
                    send(EncodedFrame(nal, pts, keyframe))
                    if (nalType in VCL_NAL_TYPES) pts += tsIncrement
                }
            } finally {
                close()
            }
        }

        awaitClose {
            proc.destroy()
            if (!proc.waitFor(SHUTDOWN_TIMEOUT_S, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        val p = processRef.getAndSet(null) ?: return
        p.destroy()
        withContext(Dispatchers.IO) {
            if (!p.waitFor(SHUTDOWN_TIMEOUT_S, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    internal companion object {
        const val DEFAULT_FRAMERATE = 30
        const val DEFAULT_BITRATE = 2_500_000
        const val RTP_VIDEO_CLOCK_HZ = 90_000
        const val SHUTDOWN_TIMEOUT_S = 2L

        // H.264 NAL unit_type values (RFC 6184 §1.3, ITU-T H.264 Table 7-1).
        const val NAL_TYPE_MASK = 0x1F
        const val NAL_TYPE_NON_IDR_SLICE = 1
        const val NAL_TYPE_IDR = 5
        val VCL_NAL_TYPES = setOf(NAL_TYPE_NON_IDR_SLICE, NAL_TYPE_IDR)

        const val ENV_FFMPEG_PATH = "PUKLIC_FFMPEG"
        val MACOS_FFMPEG_CANDIDATES = listOf(
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg",
        )

        fun locateFfmpeg(): String {
            System.getenv(ENV_FFMPEG_PATH)?.takeIf { it.isNotBlank() }?.let { return it }
            for (p in MACOS_FFMPEG_CANDIDATES) {
                if (File(p).canExecute()) return p
            }
            return "ffmpeg" // PATH fallback
        }

        fun buildCommand(
            ffmpegPath: String,
            source: ScreenSource,
            shareAudio: Boolean,
            framerate: Int,
            bitrate: Int,
            inputArgsOverride: List<String>?,
        ): List<String> {
            val inputArgs = inputArgsOverride ?: defaultInputArgs(source, shareAudio, framerate)
            return buildList {
                add(ffmpegPath)
                addAll(GLOBAL_ARGS)
                addAll(inputArgs)
                addAll(encodeArgs(bitrate))
            }
        }

        private val GLOBAL_ARGS = listOf(
            "-hide_banner", "-loglevel", "error", "-nostdin",
        )

        private fun defaultInputArgs(
            source: ScreenSource,
            shareAudio: Boolean,
            framerate: Int,
        ): List<String> {
            val osName = System.getProperty("os.name").orEmpty()
            return when {
                osName.startsWith("Mac") -> macOsInputArgs(source, shareAudio, framerate)
                else -> error("Screen capture on '$osName' is not supported yet (Phase 4.1: PipeWire portal).")
            }
        }

        private fun macOsInputArgs(
            source: ScreenSource,
            shareAudio: Boolean,
            framerate: Int,
        ): List<String> {
            val audioSpec = if (shareAudio) BLACKHOLE_DEVICE_NAME else "none"
            val avInput = "${source.id}:$audioSpec"
            return listOf(
                "-f", "avfoundation",
                "-capture_cursor", "1",
                "-framerate", framerate.toString(),
                "-pixel_format", "nv12",
                "-i", avInput,
            )
        }

        private fun encodeArgs(bitrate: Int): List<String> = listOf(
            "-vf", MAX_WIDTH_FILTER,
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-profile:v", "baseline",
            "-x264-params", X264_PARAMS,
            "-b:v", "$bitrate",
            "-maxrate", "$bitrate",
            "-bufsize", "${bitrate / BUFSIZE_DIVISOR}",
            "-pix_fmt", "yuv420p",
            "-f", "h264", "pipe:1",
        )

        private const val MAX_WIDTH_FILTER = "scale=w=min(1920\\,iw):h=-2"
        private const val X264_PARAMS = "keyint=60:min-keyint=60:scenecut=0:repeat-headers=1"
        private const val BUFSIZE_DIVISOR = 2
        private const val BLACKHOLE_DEVICE_NAME = "blackhole-2ch"
    }
}

/** Convenience constructor used by `ScreenShareClient` (slice 5). */
internal fun ffmpegVideoEncoder(source: ScreenSource, shareAudio: Boolean): VideoEncoder =
    FfmpegVideoEncoder(source = source, shareAudio = shareAudio)
