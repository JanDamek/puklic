package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.gateway.VoiceGatewayTransport
import dev.puklic.voice.gateway.VoiceGatewayTransportFactory
import dev.puklic.voice.gateway.VoiceWsTransportFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Behavioural tests for outgoing DM 1:1 voice (issue #16 Slice 1 — architect report v2
 * `docs/03_infrastructure/architect-reports/2026-05-24-dm-voice-outgoing.md`).
 *
 * Focus is the pre-voice-WS handshake: Op 4 send, VOICE_STATE_UPDATE → Ringing, timeouts,
 * cancel revocation, VoiceBusyException. The voice WS opening path is not exercised here —
 * the fake transport never returns a transport instance (suspends forever) so the test
 * completes by inspecting `state` transitions and the Op 4 audit log.
 *
 * Uses [runCurrent] (NOT `advanceUntilIdle`) for sequencing because the production code uses
 * `withTimeoutOrNull(10_000)` and `advanceUntilIdle` would advance virtual time past those
 * windows, prematurely firing the timeouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVoiceClientDmTest {

    private val selfId = UserId(7L)
    private val dmChannel = ChannelId(123L)
    private val guildId = GuildId(99L)
    private val guildChannel = ChannelId(456L)

    private lateinit var parent: Job

    @AfterTest
    fun teardown() {
        if (this::parent.isInitialized) parent.cancel()
    }

    @Test
    fun connect_dm_sends_op4_with_null_guild_id() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = null, channelId = dmChannel) }
        runCurrent()

        bridge.sentOp4.shouldBeInstanceOf<List<FakeBridge.Op4>>()
        val op = bridge.sentOp4.first()
        op.guildId shouldBe null
        op.channelId shouldBe dmChannel
        job.cancel()
    }

    @Test
    fun dm_handshake_transitions_idle_connecting_ringing() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = null, channelId = dmChannel) }
        runCurrent()
        // After Op 4 sent, before VOICE_STATE_UPDATE — Connecting.
        (client.state.value is VoiceState.Connecting) shouldBe true

        // Deliver VOICE_STATE_UPDATE for self → state transitions to Ringing.
        bridge.emitVoiceStateUpdate(
            MainGatewayBridge.VoiceStateUpdate(
                guildId = null, channelId = dmChannel, userId = selfId, sessionId = "sess-1",
            ),
        )
        runCurrent()
        (client.state.value is VoiceState.Ringing) shouldBe true
        job.cancel()
    }

    @Test
    fun dm_ringing_times_out_to_failed_no_answer() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = null, channelId = dmChannel) }
        runCurrent()
        bridge.emitVoiceStateUpdate(
            MainGatewayBridge.VoiceStateUpdate(
                guildId = null, channelId = dmChannel, userId = selfId, sessionId = "sess-1",
            ),
        )
        runCurrent()
        (client.state.value is VoiceState.Ringing) shouldBe true
        // No VOICE_SERVER_UPDATE arrives — 30 s timeout fires.
        advanceTimeBy(31_000L)
        runCurrent()

        val st = client.state.value
        (st is VoiceState.Failed) shouldBe true
        (st as VoiceState.Failed).reason shouldBe "No answer"
        job.cancel()
    }

    @Test
    fun connecting_times_out_when_no_voice_state_update() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = null, channelId = dmChannel) }
        runCurrent()
        // No VOICE_STATE_UPDATE arrives within 10 s.
        advanceTimeBy(11_000L)
        runCurrent()

        val st = client.state.value
        (st is VoiceState.Failed) shouldBe true
        (st as VoiceState.Failed).reason shouldBe "Couldn't reach recipient"
        job.cancel()
    }

    @Test
    fun second_connect_while_active_throws_voice_busy_exception() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = guildId, channelId = guildChannel) }
        runCurrent()
        check(client.state.value is VoiceState.Connecting) {
            "precondition: expected Connecting, got ${client.state.value}"
        }
        assertFailsWith<VoiceBusyException> {
            client.connect(guildId = null, channelId = dmChannel)
        }
        job.cancel()
    }

    @Test
    fun cancel_during_ringing_sends_op4_with_null_channel_id() = runTest {
        val bridge = FakeBridge()
        val client = newClient(this, bridge)

        val job = launch { client.connect(guildId = null, channelId = dmChannel) }
        runCurrent()
        bridge.emitVoiceStateUpdate(
            MainGatewayBridge.VoiceStateUpdate(
                guildId = null, channelId = dmChannel, userId = selfId, sessionId = "sess-1",
            ),
        )
        runCurrent()
        (client.state.value is VoiceState.Ringing) shouldBe true

        bridge.sentOp4.clear()
        client.disconnect()
        runCurrent()

        // Revocation Op 4 with channel_id = null should have been sent.
        val revoke = bridge.sentOp4.firstOrNull { it.channelId == null }
        (revoke != null) shouldBe true
        (client.state.value is VoiceState.Idle) shouldBe true
        job.cancel()
    }

    private fun newClient(scope: kotlinx.coroutines.test.TestScope, bridge: FakeBridge): DefaultVoiceClient {
        parent = Job()
        val appScope = CoroutineScope(scope.coroutineContext + parent)
        return DefaultVoiceClient(
            applicationScope = appScope,
            mainGateway = bridge,
            selfUserIdProvider = { selfId },
            voiceTransportFactory = neverConnectingTransportFactory(),
        )
    }

    private fun neverConnectingTransportFactory(): VoiceWsTransportFactory {
        val inner: VoiceGatewayTransportFactory = { _ ->
            CompletableDeferred<VoiceGatewayTransport>().await()
        }
        return VoiceWsTransportFactory(inner)
    }
}

private class FakeBridge : MainGatewayBridge {
    data class Op4(
        val guildId: GuildId?,
        val channelId: ChannelId?,
        val selfMute: Boolean,
        val selfDeaf: Boolean,
    )

    val sentOp4: MutableList<Op4> = mutableListOf()

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

    override suspend fun stopRinging(
        channelId: ChannelId,
        recipients: List<UserId>,
    ) { /* unused in this test */ }

    override suspend fun resolveMessageAuthor(
        channelId: ChannelId,
        messageId: dev.puklic.ids.MessageId,
    ): UserId? = null

    override suspend fun sendVoiceStateUpdate(
        guildId: GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    ) {
        sentOp4.add(Op4(guildId, channelId, selfMute, selfDeaf))
    }

    suspend fun emitVoiceStateUpdate(ev: MainGatewayBridge.VoiceStateUpdate) {
        _voiceStateUpdates.emit(ev)
    }

    suspend fun emitVoiceServerUpdate(ev: MainGatewayBridge.VoiceServerUpdate) {
        _voiceServerUpdates.emit(ev)
    }
}
