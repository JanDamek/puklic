package dev.puklic.voice.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3.1d wiring tests for DAVE routing inside [DefaultVoiceGatewayConnection]:
 *
 *  - Binary frames received on the WS binary channel get parsed as
 *    `(seq:u16 BE)(op:u8) || payload` and dispatched to the installed binary handler.
 *  - JSON envelopes with op in `[21, 24]` or `op == 31` get dispatched to the JSON
 *    handler, with their `d` JsonElement payload intact.
 *  - [DefaultVoiceGatewayConnection.sendBinary] writes the raw bytes through the
 *    transport.
 *  - IDENTIFY now carries `max_dave_protocol_version: 1`.
 *  - SESSION_DESCRIPTION decodes `dave_protocol_version`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaveRoutingTest {

    private class FakeTransport : VoiceGatewayTransport {
        val inbox = Channel<VoiceFrameIn>(Channel.UNLIMITED)
        val sentText = mutableListOf<String>()
        val sentBinary = mutableListOf<ByteArray>()
        override val incoming: Flow<VoiceFrameIn> = inbox.consumeAsFlow()
        override suspend fun sendText(text: String) { sentText.add(text) }
        override suspend fun sendBinary(bytes: ByteArray) { sentBinary.add(bytes) }
        override suspend fun close(code: Int, reason: String) {
            inbox.send(VoiceFrameIn.Close(code, reason))
            inbox.close()
        }
        fun deliver(frame: VoiceFrameIn) { inbox.trySend(frame) }
    }

    private fun TestScope.newGateway(transport: FakeTransport): VoiceGatewayConnection =
        DefaultVoiceGatewayConnection(
            scope = this,
            transportFactory = { transport },
            // Block heartbeats so the gateway worker doesn't spin forever after close().
            sleep = { awaitCancellation() },
        )

    @Test
    fun identify_carries_max_dave_protocol_version() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess", "guild", "user")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sentText.any { it.contains("\"op\":0") } }
        val identify = transport.sentText.first { it.contains("\"op\":0") }
        assertTrue(
            identify.contains("\"max_dave_protocol_version\":1"),
            "IDENTIFY missing max_dave_protocol_version: $identify",
        )
        gw.close()
    }

    @Test
    fun binary_frame_routes_to_handler() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        val captured = CompletableDeferred<Pair<Int, ByteArray>>()
        gw.setDaveBinaryHandler { op, payload -> captured.complete(op to payload) }
        gw.connect("wss://voice.test", "tok", "sess", "guild", "user")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))

        // op 25 (MLS_EXTERNAL_SENDER_PACKAGE) with arbitrary payload [0xAA, 0xBB].
        val frame = byteArrayOf(0x00, 0x07, 25, 0xAA.toByte(), 0xBB.toByte())
        transport.deliver(VoiceFrameIn.Binary(frame))

        val (op, payload) = withTimeout(2_000) { captured.await() }
        assertEquals(25, op)
        assertEquals(2, payload.size)
        assertEquals(0xAA.toByte(), payload[0])
        assertEquals(0xBB.toByte(), payload[1])
        gw.close()
    }

    @Test
    fun json_op21_routes_to_handler() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        val captured = CompletableDeferred<Pair<Int, JsonElement>>()
        gw.setDaveJsonHandler { op, body -> captured.complete(op to body) }
        gw.connect("wss://voice.test", "tok", "sess", "guild", "user")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        transport.deliver(
            VoiceFrameIn.Text("""{"op":21,"d":{"protocol_version":1,"transition_id":7}}"""),
        )
        val (op, body) = withTimeout(2_000) { captured.await() }
        assertEquals(21, op)
        assertTrue(body.toString().contains("\"transition_id\":7"))
        gw.close()
    }

    @Test
    fun send_binary_writes_through_transport() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess", "guild", "user")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sentText.any { it.contains("\"op\":0") } }

        val payload = byteArrayOf(0x01, 0x02, 0x03, 26, 0xFF.toByte())
        gw.sendBinary(payload)
        assertEquals(1, transport.sentBinary.size)
        assertTrue(transport.sentBinary[0].contentEquals(payload))
        gw.close()
    }

    @Test
    fun session_description_carries_dave_protocol_version() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess", "guild", "user")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        transport.deliver(
            VoiceFrameIn.Text(
                """{"op":4,"d":{"mode":"aead_xchacha20_poly1305_rtpsize",""" +
                    """"secret_key":[1,2,3],"dave_protocol_version":1}}""",
            ),
        )
        waitFor { received.any { it is VoiceGatewayEvent.SessionDescription } }
        val sd = received.first { it is VoiceGatewayEvent.SessionDescription }
            as VoiceGatewayEvent.SessionDescription
        assertEquals(1, sd.daveProtocolVersion)
        collectJob.cancel()
        gw.close()
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) yield()
        }
    }
}
