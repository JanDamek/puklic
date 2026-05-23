package dev.puklic.voice.screenshare

/**
 * A capturable screen source (monitor or window) discovered by a platform enumerator.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §2,
 * the public `ScreenShareClient` exposes sources by stable id + display name. The encoder
 * facade ([dev.puklic.voice.screenshare.encoder.VideoEncoder]) only consumes [id], which on
 * macOS is the ffmpeg `-f avfoundation` device index.
 *
 * Linux (Phase 4.1) will set [id] to a PipeWire node id obtained from the screencast portal.
 */
public sealed interface ScreenSource {
    public val id: String
    public val displayName: String

    public data class Monitor(
        override val id: String,
        override val displayName: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : ScreenSource

    public data class Window(
        override val id: String,
        override val displayName: String,
        val appName: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : ScreenSource
}
