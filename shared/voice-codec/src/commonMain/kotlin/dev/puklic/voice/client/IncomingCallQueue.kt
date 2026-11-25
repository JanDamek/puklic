package dev.puklic.voice.client

import co.touchlab.kermit.Logger
import dev.puklic.ids.ChannelId
import dev.puklic.voice.IncomingCall
import dev.puklic.voice.MainGatewayBridge.CallEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * FIFO queue of DM calls currently ringing for the local user, fed by the main gateway call events.
 * It outlives any single voice session because calls can arrive while the client is idle.
 */
internal class IncomingCallQueue(
    scope: CoroutineScope,
    private val signalling: VoiceSignalling,
) {
    private val _calls = MutableStateFlow<List<IncomingCall>>(emptyList())
    val calls: StateFlow<List<IncomingCall>> = _calls.asStateFlow()

    init {
        signalling.mainGateway.callEvents.onEach(::handle).launchIn(scope)
    }

    fun contains(channelId: ChannelId): Boolean = _calls.value.any { it.channelId == channelId }

    fun remove(channelId: ChannelId) = _calls.update { queue -> queue.filterNot { it.channelId == channelId } }

    private suspend fun handle(event: CallEvent) {
        val selfId = signalling.selfUserIdProvider() ?: return
        when (event) {
            is CallEvent.Started -> if (selfId in event.ringing) enqueue(event)
            is CallEvent.RingingUpdated -> if (selfId !in event.ringing) remove(event.channelId)
            is CallEvent.Ended -> remove(event.channelId)
        }
    }

    private suspend fun enqueue(event: CallEvent.Started) {
        if (contains(event.channelId)) return
        val callerId = event.callerId ?: resolveAuthor(event)
        _calls.update { it + IncomingCall(channelId = event.channelId, callerId = callerId, isGroup = false) }
    }

    private suspend fun resolveAuthor(event: CallEvent.Started) = event.messageId?.let { messageId ->
        runCatching { signalling.mainGateway.resolveMessageAuthor(event.channelId, messageId) }
            .onFailure { Logger.i(TAG) { "resolveMessageAuthor failed: ${it.message}" } }
            .getOrNull()
    }

    private companion object {
        const val TAG = "IncomingCallQueue"
    }
}
