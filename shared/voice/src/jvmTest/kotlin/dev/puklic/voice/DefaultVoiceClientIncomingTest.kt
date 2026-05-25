package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.voice.gateway.VoiceGatewayTransport
import dev.puklic.voice.gateway.VoiceGatewayTransportFactory
import dev.puklic.voice.gateway.VoiceWsTransportFactory
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * State-machine tests for the DM incoming-call queue in [DefaultVoiceClient] (issue #19).
 * Architect-report 2026-05-25-dm-incoming-voice §10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVoiceClientIncomingTest {

    private val selfId = UserId(7L)
    private val callerId = UserId(42L)
    private val dmChannel = ChannelId(123L)
    private val dmChannel2 = ChannelId(124L)
    private val dmChannel3 = ChannelId(125L)
    private val messageId = MessageId(900L)

    private lateinit var parent: Job

    @AfterTest
    fun teardown() {
        if (this::parent.isInitialized) parent.cancel()
    }

    @Test
    fun call_started_with_self_in_ringing_enqueues_incoming_call() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel,
            callerId = callerId,
            messageId = null,
            ringing = setOf(selfId),
        ))
        runCurrent()

        client.incomingCalls.value.size shouldBe 1
        client.incomingCalls.value.first().channelId shouldBe dmChannel
        client.incomingCalls.value.first().callerId shouldBe callerId
    }

    @Test
    fun call_started_with_self_not_in_ringing_is_ignored() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        // Outgoing call we initiated — Discord still fires CALL_CREATE but without us in ringing.
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel,
            callerId = selfId,
            messageId = null,
            ringing = setOf(callerId),
        ))
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
    }

    @Test
    fun call_started_falls_back_to_message_author_when_caller_id_null() = runTest {
        val bridge = IncomingFakeBridge(messageAuthor = callerId)
        val client = newClient(bridge)

        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel,
            callerId = null,
            messageId = messageId,
            ringing = setOf(selfId),
        ))
        runCurrent()

        client.incomingCalls.value.size shouldBe 1
        client.incomingCalls.value.first().callerId shouldBe callerId
        bridge.messageAuthorLookups shouldBe listOf(dmChannel to messageId)
    }

    @Test
    fun duplicate_call_started_same_channel_dedups() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        repeat(2) {
            bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
                channelId = dmChannel,
                callerId = callerId,
                messageId = null,
                ringing = setOf(selfId),
            ))
            runCurrent()
        }

        client.incomingCalls.value.size shouldBe 1
    }

    @Test
    fun ringing_updated_removing_self_drops_channel() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel, callerId = callerId, messageId = null, ringing = setOf(selfId),
        ))
        runCurrent()
        client.incomingCalls.value.size shouldBe 1

        bridge.emitCallEvent(MainGatewayBridge.CallEvent.RingingUpdated(
            channelId = dmChannel,
            ringing = emptySet(),
        ))
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
    }

    @Test
    fun call_ended_removes_channel_from_queue() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel, callerId = callerId, messageId = null, ringing = setOf(selfId),
        ))
        runCurrent()

        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Ended(dmChannel, unavailable = false))
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
    }

    @Test
    fun call_ended_for_inactive_channel_is_silent_noop() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Ended(dmChannel, unavailable = false))
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
    }

    @Test
    fun burst_of_three_distinct_callers_queues_all_in_fifo_order() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        listOf(dmChannel, dmChannel2, dmChannel3).forEach { cid ->
            bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
                channelId = cid, callerId = callerId, messageId = null, ringing = setOf(selfId),
            ))
            runCurrent()
        }

        val queue = client.incomingCalls.value
        queue.size shouldBe 3
        queue.map { it.channelId } shouldBe listOf(dmChannel, dmChannel2, dmChannel3)
    }

    @Test
    fun accept_incoming_pops_head_and_invokes_connect() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel, callerId = callerId, messageId = null, ringing = setOf(selfId),
        ))
        runCurrent()

        val job = launch { client.acceptIncoming(dmChannel) }
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
        // connect() reuses sendVoiceStateUpdate — verify Op 4 sent with null guild + matching channel.
        val op = bridge.sentOp4.firstOrNull { it.guildId == null && it.channelId == dmChannel }
        (op != null) shouldBe true
        job.cancel()
    }

    @Test
    fun decline_incoming_pops_head_and_calls_stop_ringing() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel, callerId = callerId, messageId = null, ringing = setOf(selfId),
        ))
        runCurrent()

        client.declineIncoming(dmChannel)
        runCurrent()

        client.incomingCalls.value.isEmpty() shouldBe true
        bridge.stopRingingCalls shouldBe listOf(dmChannel to listOf(selfId))
    }

    @Test
    fun decline_incoming_with_inactive_channel_is_noop() = runTest {
        val bridge = IncomingFakeBridge()
        val client = newClient(bridge)

        client.declineIncoming(dmChannel)
        runCurrent()

        bridge.stopRingingCalls.isEmpty() shouldBe true
    }

    @Test
    fun stop_ringing_404_is_swallowed_via_bridge_contract() = runTest {
        val bridge = IncomingFakeBridge(stopRingingThrowsOn404 = false)
        val client = newClient(bridge)
        bridge.emitCallEvent(MainGatewayBridge.CallEvent.Started(
            channelId = dmChannel, callerId = callerId, messageId = null, ringing = setOf(selfId),
        ))
        runCurrent()

        // The bridge contract says 404 is mapped to success — emulate by no-op stopRinging.
        client.declineIncoming(dmChannel)
        runCurrent()
        // No exception propagates from declineIncoming.
        client.incomingCalls.value.isEmpty() shouldBe true
    }

    private fun kotlinx.coroutines.test.TestScope.newClient(
        bridge: IncomingFakeBridge,
    ): DefaultVoiceClient {
        parent = Job()
        val appScope = CoroutineScope(coroutineContext + parent)
        val client = DefaultVoiceClient(
            applicationScope = appScope,
            mainGateway = bridge,
            selfUserIdProvider = { selfId },
            voiceTransportFactory = neverConnectingTransportFactory(),
        )
        // Let the init-block subscriber attach to callEvents before tests emit.
        runCurrent()
        return client
    }

    private fun neverConnectingTransportFactory(): VoiceWsTransportFactory {
        val inner: VoiceGatewayTransportFactory = { _ ->
            CompletableDeferred<VoiceGatewayTransport>().await()
        }
        return VoiceWsTransportFactory(inner)
    }
}

private class IncomingFakeBridge(
    private val messageAuthor: UserId? = null,
    @Suppress("UNUSED_PARAMETER") stopRingingThrowsOn404: Boolean = false,
) : MainGatewayBridge {

    data class Op4(
        val guildId: dev.puklic.ids.GuildId?,
        val channelId: ChannelId?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
    )

    val sentOp4: MutableList<Op4> = mutableListOf()
    val stopRingingCalls: MutableList<Pair<ChannelId, List<UserId>>> = mutableListOf()
    val messageAuthorLookups: MutableList<Pair<ChannelId, MessageId>> = mutableListOf()

    private val _voiceStateUpdates = MutableSharedFlow<MainGatewayBridge.VoiceStateUpdate>(
        replay = 0, extraBufferCapacity = 16,
    )
    override val voiceStateUpdates: SharedFlow<MainGatewayBridge.VoiceStateUpdate> =
        _voiceStateUpdates.asSharedFlow()

    private val _voiceServerUpdates = MutableSharedFlow<MainGatewayBridge.VoiceServerUpdate>(
        replay = 0, extraBufferCapacity = 16,
    )
    override val voiceServerUpdates: SharedFlow<MainGatewayBridge.VoiceServerUpdate> =
        _voiceServerUpdates.asSharedFlow()

    private val _callEvents = MutableSharedFlow<MainGatewayBridge.CallEvent>(
        replay = 0, extraBufferCapacity = 16,
    )
    override val callEvents: SharedFlow<MainGatewayBridge.CallEvent> = _callEvents.asSharedFlow()

    override suspend fun stopRinging(channelId: ChannelId, recipients: List<UserId>) {
        stopRingingCalls.add(channelId to recipients)
    }

    override suspend fun resolveMessageAuthor(
        channelId: ChannelId,
        messageId: MessageId,
    ): UserId? {
        messageAuthorLookups.add(channelId to messageId)
        return messageAuthor
    }

    override suspend fun sendVoiceStateUpdate(
        guildId: dev.puklic.ids.GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    ) {
        sentOp4.add(Op4(guildId, channelId, selfMute, selfDeaf))
    }

    suspend fun emitCallEvent(ev: MainGatewayBridge.CallEvent) {
        _callEvents.emit(ev)
    }
}
