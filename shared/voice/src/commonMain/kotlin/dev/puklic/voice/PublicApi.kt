package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * Public voice API for Puklic Phase 3.0.
 *
 * Source of truth: `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §4.
 * The domain types below are copied **verbatim** from that document and must stay in sync
 * with it. If you change anything here, update the architect report in the same commit.
 *
 * Layering rule (per repo `CLAUDE.md`):
 *  - UI / `:shared:compose-ui` may import only [VoiceClient], [VoiceState], [AudioDevice],
 *    [AudioConstants].
 *  - UI MUST NOT import `dev.puklic.voice.transport`, `dev.puklic.voice.crypto`, or any
 *    other internal package.
 */

public object AudioConstants {
    public const val SAMPLE_RATE_HZ: Int = 48_000
    public const val FRAME_DURATION_MS: Int = 20
    public const val SAMPLES_PER_FRAME: Int = 960
    public const val CHANNELS_MONO: Int = 1
    public const val BYTES_PER_SAMPLE: Int = 2
    public const val MAX_OPUS_FRAME_BYTES: Int = 4000
}

public data class AudioDevice(
    val id: String,
    val displayName: String,
    val direction: Direction,
    val isDefault: Boolean,
) {
    public enum class Direction { Capture, Playback }
}

public sealed interface VoiceState {
    public data object Idle : VoiceState
    public data class Connecting(val channelId: ChannelId, val attempt: Int) : VoiceState
    public data class Connected(
        val channelId: ChannelId,
        val ssrc: Int,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val speakers: Set<UserId>,
    ) : VoiceState
    public data class Failed(val reason: String, val recoverable: Boolean) : VoiceState
}

public interface VoiceClient {
    public val state: StateFlow<VoiceState>
    public val devices: StateFlow<List<AudioDevice>>
    public suspend fun connect(guildId: GuildId, channelId: ChannelId)
    public suspend fun disconnect()
    public fun setSelfMute(muted: Boolean)
    public fun setSelfDeaf(deaf: Boolean)
    public fun selectCaptureDevice(id: String)
    public fun selectPlaybackDevice(id: String)
}
