package dev.puklic.voice.screenshare

/**
 * Public state of the screen-share session. See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §2.
 *
 * Transitions:
 *   Idle → Negotiating(source) → Active(source, videoSsrc, withAudio)
 *                              ↘ Failed(reason)
 *   Active/Failed → Idle  (via [ScreenShareClient.stop])
 */
public sealed interface ScreenShareState {
    public data object Idle : ScreenShareState
    public data class Negotiating(val source: ScreenSource) : ScreenShareState
    public data class Active(
        val source: ScreenSource,
        val videoSsrc: Int,
        val withAudio: Boolean,
    ) : ScreenShareState
    public data class Failed(val reason: String) : ScreenShareState
}
