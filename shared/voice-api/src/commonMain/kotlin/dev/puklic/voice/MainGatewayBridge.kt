package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import kotlinx.coroutines.flow.SharedFlow

/**
 * Slim bridge so the voice client implementations do not depend on `:shared:protocol-discord` or
 * `:shared:repositories`. The wiring layer (DependencyGraph) adapts the main gateway +
 * GatewayEventSource to this interface.
 */
public interface MainGatewayBridge {
    /** Send OP 4 VOICE_STATE_UPDATE on the main gateway. */
    public suspend fun sendVoiceStateUpdate(
        guildId: GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    )

    /** Stream of `VOICE_STATE_UPDATE` dispatches (server-side echo + session id). */
    public val voiceStateUpdates: SharedFlow<VoiceStateUpdate>

    /** Stream of `VOICE_SERVER_UPDATE` dispatches (endpoint + token). */
    public val voiceServerUpdates: SharedFlow<VoiceServerUpdate>

    /**
     * DM incoming-call dispatches (CALL_CREATE / CALL_UPDATE / CALL_DELETE). See
     * architect-report 2026-05-25-dm-incoming-voice §6.
     */
    public val callEvents: SharedFlow<CallEvent>

    /**
     * Stop ringing recipients of a DM call. Used by [VoiceClient.declineIncoming]. The bridge
     * implementation MUST treat 404 / 410 as success (call already gone).
     */
    public suspend fun stopRinging(channelId: ChannelId, recipients: List<UserId>)

    /**
     * Best-effort resolve of a message's author id — used for the caller-id fallback chain
     * step 2 (architect-report 2026-05-25-dm-incoming-voice §4). Returns null on failure.
     */
    public suspend fun resolveMessageAuthor(channelId: ChannelId, messageId: dev.puklic.ids.MessageId): UserId?

    public data class VoiceStateUpdate(
        val guildId: GuildId?,
        val channelId: ChannelId?,
        val userId: UserId,
        val sessionId: String,
    )

    public data class VoiceServerUpdate(
        val guildId: GuildId?,
        val token: String,
        val endpoint: String?,
    )

    /** Tagged union of the three DM-call dispatches the voice layer cares about. */
    public sealed interface CallEvent {
        public val channelId: ChannelId

        public data class Started(
            override val channelId: ChannelId,
            val callerId: UserId?,
            val messageId: dev.puklic.ids.MessageId?,
            val ringing: Set<UserId>,
        ) : CallEvent

        public data class RingingUpdated(
            override val channelId: ChannelId,
            val ringing: Set<UserId>,
        ) : CallEvent

        public data class Ended(
            override val channelId: ChannelId,
            val unavailable: Boolean,
        ) : CallEvent
    }
}
