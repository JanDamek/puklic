package dev.puklic.voice.screenshare

import kotlinx.coroutines.flow.StateFlow

/**
 * Public screen-share facade. Sits on top of the same UDP socket + AEAD key as the audio
 * pipeline, encoding video into RTP packets keyed by `Ready.video_ssrc`. See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §2 and §10 slice 5.
 *
 * Lifecycle:
 *  - [listSources] is always callable (returns empty if voice is not connected).
 *  - [start] requires the parent voice session to be Connected; otherwise transitions to
 *    [ScreenShareState.Failed].
 *  - [stop] is idempotent.
 */
public interface ScreenShareClient {
    public val state: StateFlow<ScreenShareState>
    public suspend fun listSources(): List<ScreenSource>
    public suspend fun start(source: ScreenSource, shareAudio: Boolean)
    public suspend fun stop()
}
