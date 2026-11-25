package dev.puklic.voice.client

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.MainGatewayBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the main gateway needs to join a voice channel; [guildId] is `null` for a DM call. */
internal data class JoinRequest(val guildId: GuildId?, val channelId: ChannelId, val selfId: UserId)

/** Everything the voice gateway Identify needs, as granted by the main gateway. */
internal data class VoiceServerGrant(val sessionId: String, val token: String, val endpoint: String)

/**
 * Main-gateway half of joining voice: sends Op 4 and waits for the VOICE_STATE_UPDATE echo plus the
 * VOICE_SERVER_UPDATE. Both flows are subscribed before Op 4 goes out so a fast reply is never missed.
 */
internal class VoiceHandshake(private val gateway: MainGatewayBridge) {

    suspend fun obtainGrant(join: JoinRequest, onRinging: () -> Unit): VoiceServerGrant = coroutineScope {
        val states = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.voiceStateUpdates.first { it.isEchoOf(join) }
        }
        val servers = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.voiceServerUpdates.first { it.guildId == join.guildId && !it.endpoint.isNullOrBlank() }
        }
        try {
            gateway.sendVoiceStateUpdate(join.guildId, join.channelId, selfMute = false, selfDeaf = false)
            val state = states.awaitWithin(STATE_UPDATE_TIMEOUT_MS) ?: error(stateTimeoutMessage(join))
            if (join.guildId == null) onRinging()
            val server = servers.awaitWithin(serverTimeoutMs(join)) ?: error(serverTimeoutMessage(join))
            VoiceServerGrant(sessionId = state.sessionId, token = server.token, endpoint = checkNotNull(server.endpoint))
        } finally {
            states.cancel()
            servers.cancel()
        }
    }

    private fun MainGatewayBridge.VoiceStateUpdate.isEchoOf(join: JoinRequest) =
        userId == join.selfId && guildId == join.guildId && channelId == join.channelId

    private suspend fun <T> Deferred<T>.awaitWithin(timeoutMs: Long): T? = withTimeoutOrNull(timeoutMs) { await() }

    private fun serverTimeoutMs(join: JoinRequest) =
        if (join.guildId == null) DM_SERVER_UPDATE_TIMEOUT_MS else STATE_UPDATE_TIMEOUT_MS

    private fun stateTimeoutMessage(join: JoinRequest) =
        if (join.guildId == null) "Couldn't reach recipient" else "timed out waiting for VOICE_STATE_UPDATE"

    private fun serverTimeoutMessage(join: JoinRequest) =
        if (join.guildId == null) "No answer" else "timed out waiting for VOICE_SERVER_UPDATE"

    private companion object {
        const val STATE_UPDATE_TIMEOUT_MS = 10_000L
        const val DM_SERVER_UPDATE_TIMEOUT_MS = 30_000L
    }
}
