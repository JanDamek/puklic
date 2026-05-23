package dev.puklic.voice.gateway

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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeVoiceTransport : VoiceGatewayTransport {
    val inbox = Channel<VoiceFrameIn>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    val closedWith = CompletableDeferred<Pair<Int, String>>()

    override val incoming: Flow<VoiceFrameIn> = inbox.consumeAsFlow()
    override suspend fun sendText(text: String) { sent.add(text) }
    val sentBinary = mutableListOf<ByteArray>()
    override suspend fun sendBinary(bytes: ByteArray) { sentBinary.add(bytes) }
    override suspend fun close(code: Int, reason: String) {
        closedWith.complete(code to reason)
        inbox.send(VoiceFrameIn.Close(code, reason))
        inbox.close()
    }

    fun deliver(frame: VoiceFrameIn) { inbox.trySend(frame) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceGatewayConnectionTest {

    @Test
    fun hello_triggers_identify_with_expected_fields() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":0") } }

        val identify = transport.sent.first { it.contains("\"op\":0") }
        assertTrue(identify.contains("\"server_id\":\"guild-99\""), identify)
        assertTrue(identify.contains("\"user_id\":\"user-7\""), identify)
        assertTrue(identify.contains("\"session_id\":\"sess-1\""), identify)
        assertTrue(identify.contains("\"token\":\"tok\""), identify)

        gw.close()
    }

    @Test
    fun ready_emits_event_and_transitions_to_active() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }
        transport.deliver(
            VoiceFrameIn.Text(
                """{"op":2,"d":{"ssrc":12345,"ip":"203.0.113.7","port":50001,"modes":["aead_xchacha20_poly1305_rtpsize"]}}""",
            ),
        )

        val state = withTimeout(1_000) { gw.state.first { it is VoiceGatewayState.Active } }
        assertIs<VoiceGatewayState.Active>(state)
        assertEquals(12345, state.ssrc)

        waitFor { received.isNotEmpty() }
        val ready = received.first()
        assertIs<VoiceGatewayEvent.Ready>(ready)
        assertEquals(12345, ready.ssrc)
        assertEquals("203.0.113.7", ready.ip)
        assertEquals(50001, ready.port)
        assertEquals(listOf("aead_xchacha20_poly1305_rtpsize"), ready.modes)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun session_description_decodes_secret_key_from_u8_array() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        // Key: 0, 1, 2, ..., 254, 255 (32 bytes) — checks high bits unsigned-style.
        val keyInts = (0 until 32).map { it * 8 + 1 } // 1, 9, 17 ... 249
        val keyJson = keyInts.joinToString(",")
        transport.deliver(
            VoiceFrameIn.Text(
                """{"op":4,"d":{"mode":"aead_xchacha20_poly1305_rtpsize","secret_key":[$keyJson]}}""",
            ),
        )

        waitFor { received.any { it is VoiceGatewayEvent.SessionDescription } }
        val sd = received.first { it is VoiceGatewayEvent.SessionDescription } as VoiceGatewayEvent.SessionDescription
        assertEquals("aead_xchacha20_poly1305_rtpsize", sd.mode)
        assertContentEquals(ByteArray(32) { (it * 8 + 1).toByte() }, sd.secretKey)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun heartbeat_loop_sends_op3_with_incrementing_nonce() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        // Use a fake sleeper so we can advance heartbeats deterministically without virtual time.
        val sleepCh = Channel<Long>(Channel.UNLIMITED)
        val resumeCh = Channel<Unit>(Channel.UNLIMITED)
        val gw = DefaultVoiceGatewayConnection(
            scope = this,
            transportFactory = { transport },
            sleep = { req ->
                sleepCh.send(req)
                resumeCh.receive()
            },
        )
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":0") } } // IDENTIFY sent

        // First heartbeat tick.
        waitFor { !sleepCh.isEmpty }
        val interval1 = sleepCh.receive()
        assertEquals(13_750L, interval1)
        resumeCh.send(Unit)
        waitFor { transport.sent.any { it.contains("\"op\":3") } }
        val hb1 = transport.sent.last { it.contains("\"op\":3") }
        assertTrue(hb1.contains("\"d\":1"), hb1)

        // Ack zeroes the missed counter so the next tick doesn't trip the timeout.
        transport.deliver(VoiceFrameIn.Text("""{"op":6,"d":{"t":1}}"""))

        // Second tick — nonce should increment to 2.
        waitFor { !sleepCh.isEmpty }
        sleepCh.receive()
        val countBefore = transport.sent.count { it.contains("\"op\":3") }
        resumeCh.send(Unit)
        waitFor { transport.sent.count { it.contains("\"op\":3") } > countBefore }
        val hb2 = transport.sent.last { it.contains("\"op\":3") }
        assertTrue(hb2.contains("\"d\":2"), hb2)

        gw.close()
    }

    @Test
    fun two_missed_acks_force_reconnect() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val sleepCh = Channel<Long>(Channel.UNLIMITED)
        val resumeCh = Channel<Unit>(Channel.UNLIMITED)
        // Replace transport factory to track reconnect attempts.
        var transportsBuilt = 0
        val gw = DefaultVoiceGatewayConnection(
            scope = this,
            transportFactory = {
                transportsBuilt++
                transport
            },
            sleep = { req ->
                sleepCh.send(req)
                resumeCh.receive()
            },
        )
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.any { it.contains("\"op\":0") } }

        // First tick: missedAcks 0 → 1, send hb. No ack.
        waitFor { !sleepCh.isEmpty }
        sleepCh.receive()
        resumeCh.send(Unit)
        waitFor { transport.sent.any { it.contains("\"op\":3") } }

        // Second tick: missedAcks 1 → 2, send hb. Still no ack.
        waitFor { !sleepCh.isEmpty }
        sleepCh.receive()
        resumeCh.send(Unit)
        waitFor { transport.sent.count { it.contains("\"op\":3") } >= 2 }

        // Third tick: missedAcks == 2 → must trigger close + reconnect.
        waitFor { !sleepCh.isEmpty }
        sleepCh.receive()
        resumeCh.send(Unit)

        val state = withTimeout(2_000) {
            gw.state.first { it is VoiceGatewayState.Reconnecting || it is VoiceGatewayState.ConnectingWs && it.attempt > 1 }
        }
        assertTrue(
            state is VoiceGatewayState.Reconnecting ||
                (state is VoiceGatewayState.ConnectingWs && state.attempt > 1),
            "expected reconnect-related state, got $state",
        )

        gw.close()
    }

    @Test
    fun client_disconnect_event_propagates() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        transport.deliver(VoiceFrameIn.Text("""{"op":13,"d":{"user_id":"abc-42"}}"""))

        waitFor { received.any { it is VoiceGatewayEvent.ClientDisconnect } }
        val ev = received.first { it is VoiceGatewayEvent.ClientDisconnect } as VoiceGatewayEvent.ClientDisconnect
        assertEquals("abc-42", ev.userId)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun speaking_event_with_user_id_propagates() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        transport.deliver(VoiceFrameIn.Text("""{"op":5,"d":{"speaking":1,"delay":0,"ssrc":99,"user_id":"u-3"}}"""))

        waitFor { received.any { it is VoiceGatewayEvent.Speaking } }
        val sp = received.first { it is VoiceGatewayEvent.Speaking } as VoiceGatewayEvent.Speaking
        assertEquals("u-3", sp.userId)
        assertEquals(99, sp.ssrc)
        assertEquals(1, sp.flags)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun send_select_protocol_emits_op1_with_address_port_mode() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }

        val before = transport.sent.size
        gw.sendSelectProtocol("198.51.100.5", 49000, "aead_xchacha20_poly1305_rtpsize")
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last()
        assertTrue(frame.contains("\"op\":1"), frame)
        assertTrue(frame.contains("\"protocol\":\"udp\""), frame)
        assertTrue(frame.contains("\"address\":\"198.51.100.5\""), frame)
        assertTrue(frame.contains("\"port\":49000"), frame)
        assertTrue(frame.contains("\"mode\":\"aead_xchacha20_poly1305_rtpsize\""), frame)

        gw.close()
    }

    @Test
    fun send_speaking_emits_op5_with_bitmask() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransport()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }

        val before = transport.sent.size
        gw.sendSpeaking(speaking = 1, ssrc = 12345)
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last()
        assertTrue(frame.contains("\"op\":5"), frame)
        assertTrue(frame.contains("\"speaking\":1"), frame)
        assertTrue(frame.contains("\"ssrc\":12345"), frame)

        gw.close()
    }

    private fun TestScope.newGateway(transport: FakeVoiceTransport): VoiceGatewayConnection =
        DefaultVoiceGatewayConnection(
            scope = this,
            transportFactory = { transport },
            sleep = { /* never tick in non-heartbeat tests */ kotlinx.coroutines.awaitCancellation() },
        )

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) kotlinx.coroutines.yield()
        }
    }
}
