package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.screenshare.ScreenShareClient
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
    /** Stereo Opus — screencast audio (Linux PipeWire portal). Mic stays mono. */
    public const val CHANNELS_STEREO: Int = 2
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

    /**
     * DM call only: VOICE_STATE_UPDATE was acknowledged by Discord (recipient's client
     * received the call invite) but VOICE_SERVER_UPDATE has not arrived yet — Discord
     * delivers it once the recipient picks up. Up to 30 s in this state before transitioning
     * to [Failed]("No answer"). Guild voice channels skip this state entirely.
     */
    public data class Ringing(val channelId: ChannelId) : VoiceState

    public data class Connected(
        val channelId: ChannelId,
        val ssrc: Int,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
        val speakers: Set<UserId>,
    ) : VoiceState
    public data class Failed(val reason: String, val recoverable: Boolean) : VoiceState
}

/**
 * Thrown by [VoiceClient.connect] when the client is already in a non-terminal voice state
 * (Connecting / Ringing / Connected). The UI should surface a snackbar prompting the user
 * to leave the current call first.
 */
public class VoiceBusyException : Exception("Already in a voice call")

/**
 * One decoded frame of incoming remote video (screenshare receive — Phase 4.2).
 *
 * [rgba] is laid out as 8-bit R, G, B, A per pixel, row-major, no padding (so
 * `rgba.size == width * height * 4`). The UI layer is expected to copy this byte array into
 * a Compose `ImageBitmap` via `BufferedImage.setRGB` or equivalent.
 *
 * Public so `:shared:compose-ui` can render without importing internal transport types.
 */
public data class IncomingVideoFrame(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingVideoFrame) return false
        return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int {
        var h = width
        h = 31 * h + height
        h = 31 * h + rgba.contentHashCode()
        return h
    }
}

/**
 * UI-facing view of the DAVE E2EE state for the current voice session. Maps from
 * the internal `dev.puklic.voice.dave.DaveSession.State` so UI doesn't depend on
 * `:shared:voice-dave`.
 */
public sealed interface DaveUiState {
    /** No DAVE session (server didn't advertise it, or call is idle). */
    public data object Off : DaveUiState
    /** DAVE handshake in progress (Initialized / PreparingTransition). */
    public data object Connecting : DaveUiState
    /** DAVE active — frames are E2E-encrypted. */
    public data class Active(val epoch: Long) : DaveUiState
    /** Server advertised an unsupported version — falling back to per-hop AEAD only. */
    public data class Disabled(val reason: String) : DaveUiState
}

/**
 * Snapshot of a single DM call currently ringing on this client. Surfaced via
 * [VoiceClient.incomingCalls]; [callerId] may be null when neither voice_states nor the
 * CALL_REQUEST message-author fallback could resolve the caller (see architect-report
 * 2026-05-25-dm-incoming-voice §4 — the UI must then render a generic label).
 *
 * [isGroup] is stubbed `false` in v1 — see follow-up issue for GroupDmChannel domain support.
 */
public data class IncomingCall(
    val channelId: ChannelId,
    val callerId: UserId?,
    val isGroup: Boolean,
)

public interface VoiceClient {
    public val state: StateFlow<VoiceState>
    public val devices: StateFlow<List<AudioDevice>>
    public val screenShare: ScreenShareClient
    /** Current DAVE end-to-end encryption state. [DaveUiState.Off] when not negotiated. */
    public val daveState: StateFlow<DaveUiState>

    /**
     * Latest decoded frame per remote video SSRC. Empty when nobody in the channel is
     * screensharing. Populated as soon as the H.264 decoder produces its first frame after
     * an SPS/PPS+IDR sequence arrives.
     */
    public val incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>>

    /**
     * Initiate a voice connection. [guildId] is `null` for 1:1 DM voice calls and non-null for
     * guild voice channels. Throws [VoiceBusyException] if a session is already active.
     */
    /**
     * Ordered FIFO queue of currently ringing incoming DM calls. Head = currently shown to
     * the user. Emissions are atomic — accept / decline / remote-cancel always reduce the
     * list through a single `update {}` so collectors never see partial state.
     *
     * See architect-report 2026-05-25-dm-incoming-voice §6.
     */
    public val incomingCalls: StateFlow<List<IncomingCall>>

    /**
     * Accept the currently-ringing call for [channelId]. Removes it from [incomingCalls] and
     * reuses the standard `connect(null, channelId)` path (issue #16 Slice 1). Silent no-op
     * if the channel is no longer queued (e.g. the caller hung up first).
     */
    public suspend fun acceptIncoming(channelId: ChannelId)

    /**
     * Decline the currently-ringing call for [channelId]. Removes it from [incomingCalls] and
     * POSTs `/channels/{id}/call/stop-ringing` with `recipients=[self]`. 404 / 410 are
     * swallowed by the REST layer (the call may already be gone — architect-report §7).
     */
    public suspend fun declineIncoming(channelId: ChannelId)

    public suspend fun connect(guildId: GuildId?, channelId: ChannelId)
    public suspend fun disconnect()
    public fun setSelfMute(muted: Boolean)
    public fun setSelfDeaf(deaf: Boolean)
    public fun selectCaptureDevice(id: String)
    public fun selectPlaybackDevice(id: String)

    /**
     * Remote participants currently known to be in the active voice call,
     * indexed by Discord [UserId]. Populated from voice-gateway Op 5 (Speaking)
     * and pruned on Op 13 (ClientDisconnect) / [disconnect].
     *
     * Empty when no DAVE-eligible participants are known yet (or when idle).
     * The local user is NOT included — this is the set of remote endpoints
     * whose pairwise fingerprint can be requested via [pairwiseFingerprint].
     */
    public val participants: StateFlow<Set<UserId>>

    /**
     * Discord-spec Short Authentication String (SAS) for the pairwise DAVE
     * fingerprint between the local user and [userId]. Format: space-separated
     * 5-digit decimal groups (see `dev.puklic.voice.dave.sas.SasFormatter`).
     *
     * Returns null when DAVE is not Active, the backend cannot expose
     * fingerprints (e.g. Wire fallback), or the remote user is not in the
     * active MLS group. Safe to call from the UI; suspends because libdave
     * briefly blocks on the session mutex during a handshake.
     */
    public suspend fun pairwiseFingerprint(userId: UserId): String?
}
