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
        closedWith.complete(code to reason)
        inbox.send(GatewayFrameIn.Close(code, reason))
        inbox.close()
    }

    fun deliver(frame: GatewayFrameIn) { inbox.trySend(frame) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionTest {
    @Test
    fun hello_triggers_identify_and_ready_transitions_state() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<GatewayDispatchEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))

        // Wait until at least one frame is sent (IDENTIFY)
        waitFor { transport.sent.isNotEmpty() }
        val identifyFrame = transport.sent.first()
        assertTrue(identifyFrame.contains("\"op\":2"))
        assertTrue(identifyFrame.contains("\"browser\":\"Discord Client\""))

        // Now deliver READY
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

        // Confirm dispatch event emitted
        waitFor { received.isNotEmpty() }
        assertEquals("READY", received[0].type)

        collectJob.cancel()
        gw.disconnect()
    }

    @Test
    fun close_code_4004_transitions_to_token_invalid() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Close(CloseCode.AUTH_FAILED, "auth failed"))

        val state = withTimeout(1_000) { gw.state.first { it is GatewayState.TokenInvalid } }
        assertIs<GatewayState.TokenInvalid>(state)
        gw.disconnect()
    }

    @Test
    fun close_code_4009_after_ready_transitions_to_resuming() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        transport.deliver(
            GatewayFrameIn.Text(
                """{"op":0,"s":1,"t":"READY","d":{"session_id":"sess-X","resume_gateway_url":"wss://resume","user":{"id":"1","username":"u"},"guilds":[],"private_channels":[]}}""",
            ),
        )
        withTimeout(1_000) { gw.state.first { it is GatewayState.Ready } }

        transport.deliver(GatewayFrameIn.Close(CloseCode.SESSION_TIMEOUT, "session timeout"))
        val state = withTimeout(1_000) { gw.state.first { it is GatewayState.Resuming } }
        assertIs<GatewayState.Resuming>(state)
        assertEquals("sess-X", state.sessionId)
        assertEquals(1, state.sequence)
        gw.disconnect()
    }

    @Test
    fun lazy_request_guild_emits_op14_with_expected_shape() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://test")

        // Bring the gateway up to IDENTIFY so activeTransport is bound.
        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.isNotEmpty() } // IDENTIFY frame sent

        val before = transport.sent.size
        gw.lazyRequestGuild(
            guildId = dev.puklic.ids.GuildId(42L),
            channelIds = listOf(dev.puklic.ids.ChannelId(7L), dev.puklic.ids.ChannelId(9L)),
        )
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last()
        // Op 14 + d shape: guild_id string, channels map keyed by channel id strings, range [0,99].
        assertTrue(frame.contains("\"op\":14"), "expected op:14 frame, got: $frame")
        assertTrue(frame.contains("\"guild_id\":\"42\""), frame)
        assertTrue(frame.contains("\"typing\":true"), frame)
        assertTrue(frame.contains("\"threads\":true"), frame)
        assertTrue(frame.contains("\"activities\":true"), frame)
        assertTrue(frame.contains("\"7\":[[0,99]]"), frame)
        assertTrue(frame.contains("\"9\":[[0,99]]"), frame)
        gw.disconnect()
    }

    @Test
    fun server_heartbeat_request_is_answered() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://test")

        transport.deliver(GatewayFrameIn.Text("""{"op":10,"d":{"heartbeat_interval":45000}}"""))
        waitFor { transport.sent.isNotEmpty() } // identify
        val identifyCount = transport.sent.size
        transport.deliver(GatewayFrameIn.Text("""{"op":1}"""))
        waitFor { transport.sent.size > identifyCount }
        val heartbeatFrame = transport.sent.last()
        assertTrue(heartbeatFrame.contains("\"op\":1"))
        gw.disconnect()
    }

    private fun TestScope.newGateway(transport: FakeTransport): GatewayConnection =
        GatewayConnection(
            scope = this,
            token = "tok",
            transportFactory = { transport },
            now = { 0L },
        )

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(1_000) {
            while (!predicate()) kotlinx.coroutines.yield()
        }
    }
}
