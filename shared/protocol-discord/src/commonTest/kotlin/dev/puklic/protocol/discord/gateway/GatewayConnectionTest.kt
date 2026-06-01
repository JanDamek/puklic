package dev.puklic.protocol.discord.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeTransport : GatewayTransport {
    val inbox = Channel<GatewayFrameIn>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    val closedWith = CompletableDeferred<Pair<Int, String>>()

    override val incoming: Flow<GatewayFrameIn> = inbox.consumeAsFlow()
    override suspend fun sendText(text: String) { sent.add(text) }
    override suspend fun close(code: Int, reason: String) {
        if (!closedWith.isCompleted) closedWith.complete(code to reason)
        runCatching {
            inbox.send(GatewayFrameIn.Close(code, reason))
        }
        inbox.close()
    }

    fun deliver(frame: GatewayFrameIn) { inbox.trySend(frame) }
}

/**
 * Factory that hands out a sequence of [FakeTransport] instances — one per connect attempt.
 * Tests can append additional transports for reconnect scenarios; if the queue runs out, a
 * fresh transport is fabricated so tight reconnect loops do not block the test.
 */
private class FakeTransportFactory(initial: FakeTransport) {
    private val queue: ArrayDeque<FakeTransport> = ArrayDeque<FakeTransport>().apply { addLast(initial) }
    val opened: MutableList<Pair<String, FakeTransport>> = mutableListOf()
    val factory: GatewayTransportFactory = { url ->
        val t = if (queue.isNotEmpty()) queue.removeFirst() else FakeTransport()
        opened.add(url to t)
        t
    }
    fun enqueue(t: FakeTransport) { queue.addLast(t) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionTest {
    @Test
    fun hello_triggers_identify_and_ready_transitions_state() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        val received = mutableListOf<GatewayDispatchEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))

        waitFor { transport.sent.any { it.contains("\"op\":2") } }
        val identifyFrame = transport.sent.first { it.contains("\"op\":2") }
        assertTrue(identifyFrame.contains("\"browser\":\"Discord Client\""))

        transport.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-1","resume_gateway_url":"wss://resume","user":{"id":"42","username":"me"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        val ready = withTimeout(1_000) {
            gw.state.first { it is GatewayState.Ready }
        }
        assertIs<GatewayState.Ready>(ready)
        assertEquals("sess-1", ready.sessionId)
        assertEquals("wss://resume", ready.resumeGatewayUrl)

        waitFor { received.isNotEmpty() }
        assertEquals("READY", received[0].type)

        collectJob.cancel()
        gw.disconnect()
    }

    @Test
    fun close_code_4004_transitions_to_token_invalid_and_does_not_reconnect() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val factory = FakeTransportFactory(first)
        val gw = newGateway(factory.factory)
        gw.connect("wss://test")

        first.deliver(GatewayFrameIn.Close(CloseCode.AUTH_FAILED, "auth failed"))

        val state = withTimeout(1_000) { gw.state.first { it is GatewayState.TokenInvalid } }
        assertIs<GatewayState.TokenInvalid>(state)
        // No second connect attempt — TokenInvalid is terminal.
        assertEquals(1, factory.opened.size)
        gw.disconnect()
    }

    @Test
    fun close_code_4009_after_ready_transitions_to_resuming() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val factory = FakeTransportFactory(first)
        // Use a backoff long enough that the Resuming state is observable before the supervisor
        // restarts and flips state to Connecting on the next iteration.
        val gw = GatewayConnection(
            scope = this,
            token = "tok",
            transportFactory = factory.factory,
            now = { 0L },
            reconnectBackoffMs = longArrayOf(60_000L),
        )
        // Collect all state transitions so we can assert Resuming was passed through even if the
        // supervisor moves past it quickly.
        val states = mutableListOf<GatewayState>()
        val stateJob = launch { gw.state.collect { states.add(it) } }
        gw.connect("wss://test")

        first.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        first.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-X","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        withTimeout(1_000) { gw.state.first { it is GatewayState.Ready } }

        first.deliver(GatewayFrameIn.Close(CloseCode.SESSION_TIMEOUT, "session timeout"))

        waitFor(5_000) { states.any { it is GatewayState.Resuming } }
        val resuming = states.first { it is GatewayState.Resuming } as GatewayState.Resuming
        assertEquals("sess-X", resuming.sessionId)
        assertEquals(1, resuming.sequence)
        stateJob.cancel()
        gw.disconnect()
    }

    @Test
    fun guild_subscriptions_bulk_emits_op37_with_expected_shape() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.isNotEmpty() }

        val before = transport.sent.size
        gw.guildSubscriptionsBulk(
            mapOf(
                dev.puklic.ids.GuildId(42L) to listOf(
                    dev.puklic.ids.ChannelId(7L),
                    dev.puklic.ids.ChannelId(9L),
                ),
                dev.puklic.ids.GuildId(43L) to listOf(dev.puklic.ids.ChannelId(11L)),
            ),
        )
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last { it.contains("\"op\":37") }
        assertTrue(frame.contains("\"subscriptions\""), frame)
        assertTrue(frame.contains("\"42\""), frame)
        assertTrue(frame.contains("\"43\""), frame)
        assertTrue(frame.contains("\"typing\":true"), frame)
        assertTrue(frame.contains("\"threads\":true"), frame)
        assertTrue(frame.contains("\"activities\":true"), frame)
        assertTrue(frame.contains("\"7\":[[0,99]]"), frame)
        assertTrue(frame.contains("\"9\":[[0,99]]"), frame)
        assertTrue(frame.contains("\"11\":[[0,99]]"), frame)
        gw.disconnect()
    }

    @Test
    fun lazy_request_guild_delegates_to_op37_for_back_compat() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.isNotEmpty() }
        gw.lazyRequestGuild(dev.puklic.ids.GuildId(1L), listOf(dev.puklic.ids.ChannelId(2L)))
        waitFor { transport.sent.any { it.contains("\"op\":37") } }
        val frame = transport.sent.last { it.contains("\"op\":37") }
        assertTrue(frame.contains("\"1\""), frame)
        assertTrue(frame.contains("\"2\":[[0,99]]"), frame)
        gw.disconnect()
    }

    @Test
    fun identify_payload_includes_presence_and_client_state() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":2") } }
        val identify = transport.sent.first { it.contains("\"op\":2") }
        assertTrue(identify.contains("\"presence\""), "IDENTIFY must include presence: $identify")
        assertTrue(identify.contains("\"status\":\"unknown\""), identify)
        assertTrue(identify.contains("\"client_state\""), "IDENTIFY must include client_state: $identify")
        assertTrue(identify.contains("\"capabilities\":1734653"), "modern capabilities expected: $identify")
        gw.disconnect()
    }

    @Test
    fun server_heartbeat_request_is_answered() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":2") } } // identify
        val identifyCount = transport.sent.size
        transport.deliver(GatewayFrameIn.Text("""{"op":1}"""))
        waitFor { transport.sent.size > identifyCount }
        assertTrue(transport.sent.any { it.contains("\"op\":1") })
        gw.disconnect()
    }

    @Test
    fun send_voice_state_update_emits_op4_with_expected_shape() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":2") } }

        gw.sendVoiceStateUpdate(
            guildId = dev.puklic.ids.GuildId(100L),
            channelId = dev.puklic.ids.ChannelId(200L),
            selfMute = false,
            selfDeaf = false,
        )
        waitFor { transport.sent.any { it.contains("\"op\":4") } }
        val frame = transport.sent.last { it.contains("\"op\":4") }
        assertTrue(frame.contains("\"guild_id\":\"100\""), frame)
        assertTrue(frame.contains("\"channel_id\":\"200\""), frame)
        assertTrue(frame.contains("\"self_mute\":false"), frame)
        assertTrue(frame.contains("\"self_deaf\":false"), frame)
        gw.disconnect()
    }

    @Test
    fun send_voice_state_update_with_null_channel_signals_leave() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory)
        gw.connect("wss://test")
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":2") } }

        gw.sendVoiceStateUpdate(
            guildId = dev.puklic.ids.GuildId(100L),
            channelId = null,
            selfMute = false,
            selfDeaf = false,
        )
        waitFor { transport.sent.any { it.contains("\"op\":4") } }
        val frame = transport.sent.last { it.contains("\"op\":4") }
        assertTrue(frame.contains("\"channel_id\":null"), frame)
        gw.disconnect()
    }

    // --- Resilience tests (#82) ---------------------------------------------------------

    @Test
    fun client_sends_autonomous_heartbeat_after_hello() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(FakeTransportFactory(transport).factory, heartbeatScale = 0.001)
        gw.connect("wss://test")
        // Large interval so the only beat that fires within the test window is the first one;
        // we verify the autonomous heartbeat loop exists by observing >=1 OP 1 frame WITHOUT
        // any server OP 1 prompt.
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":1000}}"""))

        waitFor(5_000) { transport.sent.any { it.contains("\"op\":1") } }
        assertTrue(transport.sent.any { it.contains("\"op\":1") }, "expected autonomous heartbeat")
        // Send ACK so the next tick does not trigger zombie close before disconnect.
        transport.deliver(GatewayFrameIn.Text("""{"op":11}"""))
        gw.disconnect()
    }

    @Test
    fun missed_heartbeat_ack_closes_connection_for_reconnect() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val second = FakeTransport()
        val factory = FakeTransportFactory(first).also { it.enqueue(second) }
        val gw = newGateway(factory.factory, heartbeatScale = 0.01)
        gw.connect("wss://test")

        first.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":50}}"""))
        first.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-Z","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        // Never deliver HEARTBEAT_ACK. After the next tick, gateway must close-with-4000
        // and the supervisor reconnects → factory.opened grows to 2.
        waitFor(5_000) { factory.opened.size >= 2 }
        assertTrue(factory.opened.size >= 2, "expected supervisor reconnect after zombie close")
        // Second connect uses resume_gateway_url.
        assertEquals("wss://resume", factory.opened[1].first)
        gw.disconnect()
    }

    @Test
    fun resumable_close_reconnects_with_resume_url_and_sends_op6() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val second = FakeTransport()
        val factory = FakeTransportFactory(first).also { it.enqueue(second) }
        val gw = newGateway(factory.factory)
        gw.connect("wss://test")

        first.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        first.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":5,"t":"READY","d":{"session_id":"sess-R","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        withTimeout(1_000) { gw.state.first { it is GatewayState.Ready } }

        first.deliver(GatewayFrameIn.Close(CloseCode.SESSION_TIMEOUT, "session timeout"))

        waitFor(5_000) { factory.opened.size >= 2 }
        assertEquals("wss://resume", factory.opened[1].first)

        // After HELLO on the second transport, client should send OP 6 RESUME (not OP 2).
        second.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor(5_000) { second.sent.isNotEmpty() }
        val first2 = second.sent.first()
        assertTrue(first2.contains("\"op\":6"), "expected RESUME (op 6), got: $first2")
        assertTrue(first2.contains("\"session_id\":\"sess-R\""), first2)
        assertTrue(first2.contains("\"seq\":5"), first2)
        gw.disconnect()
    }

    @Test
    fun invalid_session_clears_state_and_reidentifies_next_connect() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val second = FakeTransport()
        val factory = FakeTransportFactory(first).also { it.enqueue(second) }
        val gw = newGateway(factory.factory)
        gw.connect("wss://test")

        first.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        first.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-IS","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        withTimeout(1_000) { gw.state.first { it is GatewayState.Ready } }

        // Server invalidates the session.
        first.deliver(GatewayFrameIn.Text("""{"op":9,"d":false}"""))

        waitFor(5_000) { factory.opened.size >= 2 }
        // Re-IDENTIFY goes against the default gateway, NOT the prior resume URL.
        assertTrue(
            factory.opened[1].first.startsWith("wss://gateway.discord.gg"),
            "expected default gateway URL on re-identify, got ${factory.opened[1].first}",
        )

        second.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor(5_000) { second.sent.isNotEmpty() }
        val firstFrame = second.sent.first()
        assertTrue(firstFrame.contains("\"op\":2"), "expected fresh IDENTIFY, got: $firstFrame")
        gw.disconnect()
    }

    @Test
    fun manual_disconnect_does_not_reconnect() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTransport()
        val factory = FakeTransportFactory(first)
        val gw = newGateway(factory.factory)
        gw.connect("wss://test")
        first.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        first.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-D","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        withTimeout(1_000) { gw.state.first { it is GatewayState.Ready } }
        gw.disconnect()
        // No further connect attempt.
        assertEquals(1, factory.opened.size)
    }

    private fun TestScope.newGateway(
        factory: GatewayTransportFactory,
        heartbeatScale: Double = 1.0,
    ): GatewayConnection =
        GatewayConnection(
            scope = this,
            token = "tok",
            transportFactory = factory,
            now = { 0L },
            heartbeatIntervalScale = heartbeatScale,
            // Instant retries in tests — backoff is tested implicitly by production code paths,
            // not by these unit tests.
            reconnectBackoffMs = longArrayOf(0L),
        )

    private suspend fun waitFor(timeoutMs: Long = 1_000L, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) kotlinx.coroutines.delay(1)
        }
    }
}
