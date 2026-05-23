package dev.puklic.voice.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Slice 1 — screenshare DTO + protocol scaffold.
 *
 * Source of truth: `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §4-5.
 */
private class FakeVoiceTransportV : VoiceGatewayTransport {
    val inbox = Channel<VoiceFrameIn>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    val closedWith = CompletableDeferred<Pair<Int, String>>()
    override val incoming: Flow<VoiceFrameIn> = inbox.consumeAsFlow()
    override suspend fun sendText(text: String) { sent.add(text) }
    override suspend fun close(code: Int, reason: String) {
        closedWith.complete(code to reason)
        inbox.send(VoiceFrameIn.Close(code, reason))
        inbox.close()
    }
    fun deliver(frame: VoiceFrameIn) { inbox.trySend(frame) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VideoProtocolTest {

    @Test
    fun ready_with_video_ssrc_populates_field() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransportV()
        val gw = newGateway(transport)
        val received = mutableListOf<VoiceGatewayEvent>()
        val collectJob = launch { gw.events.collect { received.add(it) } }
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")

        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }
        transport.deliver(
            VoiceFrameIn.Text(
                """{"op":2,"d":{"ssrc":12345,"video_ssrc":67890,"ip":"203.0.113.7","port":50001,"modes":["aead_xchacha20_poly1305_rtpsize"]}}""",
            ),
        )

        waitFor { received.any { it is VoiceGatewayEvent.Ready } }
        val ready = received.first { it is VoiceGatewayEvent.Ready } as VoiceGatewayEvent.Ready
        assertEquals(12345, ready.ssrc)
        assertEquals(67890, ready.videoSsrc)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun ready_without_video_ssrc_defaults_to_zero() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransportV()
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

        waitFor { received.any { it is VoiceGatewayEvent.Ready } }
        val ready = received.first { it is VoiceGatewayEvent.Ready } as VoiceGatewayEvent.Ready
        assertEquals(0, ready.videoSsrc)

        collectJob.cancel()
        gw.close()
    }

    @Test
    fun select_protocol_codecs_list_includes_h264_and_vp8() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransportV()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }

        val before = transport.sent.size
        gw.sendSelectProtocol("198.51.100.5", 49000, "aead_xchacha20_poly1305_rtpsize")
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last()
        assertTrue(frame.contains("\"op\":1"), frame)
        assertTrue(frame.contains("\"codecs\""), "codecs array missing: $frame")
        assertTrue(frame.contains("\"name\":\"H264\""), frame)
        assertTrue(frame.contains("\"name\":\"VP8\""), frame)
        assertTrue(frame.contains("\"payload_type\":101"), frame)
        assertTrue(frame.contains("\"rtx_payload_type\":102"), frame)
        assertTrue(frame.contains("\"payload_type\":103"), frame)
        assertTrue(frame.contains("\"rtx_payload_type\":104"), frame)
        assertTrue(frame.contains("\"type\":\"video\""), frame)

        gw.close()
    }

    @Test
    fun send_video_stream_emits_op12_with_expected_structure() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeVoiceTransportV()
        val gw = newGateway(transport)
        gw.connect("wss://voice.test", "tok", "sess-1", "guild-99", "user-7")
        transport.deliver(VoiceFrameIn.Text("""{"op":8,"d":{"heartbeat_interval":13750.0}}"""))
        waitFor { transport.sent.isNotEmpty() }

        val before = transport.sent.size
        gw.sendVideoStream(audioSsrc = 12345, videoSsrc = 67890, rtxSsrc = 67891, active = true)
        waitFor { transport.sent.size > before }
        val frame = transport.sent.last()
        assertTrue(frame.contains("\"op\":12"), frame)
        assertTrue(frame.contains("\"audio_ssrc\":12345"), frame)
        assertTrue(frame.contains("\"video_ssrc\":67890"), frame)
        assertTrue(frame.contains("\"rtx_ssrc\":67891"), frame)
        assertTrue(frame.contains("\"streams\""), frame)
        assertTrue(frame.contains("\"type\":\"video\""), frame)
        assertTrue(frame.contains("\"rid\":\"100\""), frame)
        assertTrue(frame.contains("\"quality\":100"), frame)
        assertTrue(frame.contains("\"max_bitrate\":2500000"), frame)
        assertTrue(frame.contains("\"active\":true"), frame)

        // Validate it parses back to the typed payload.
        val json = Json { ignoreUnknownKeys = true }
        // Extract d object.
        val dStart = frame.indexOf("\"d\":") + 4
        val dJson = frame.substring(dStart, frame.lastIndexOf('}'))
        val parsed = json.decodeFromString(Op12VideoStream.serializer(), dJson)
        assertEquals(12345, parsed.audioSsrc)
        assertEquals(67890, parsed.videoSsrc)
        assertEquals(67891, parsed.rtxSsrc)
        assertEquals(1, parsed.streams.size)
        assertEquals(67890, parsed.streams[0].ssrc)
        assertEquals(67891, parsed.streams[0].rtxSsrc)
        assertTrue(parsed.streams[0].active)

        gw.close()
    }

    @Test
    fun video_stream_op_code_is_twelve() {
        assertEquals(12, VoiceOp.VIDEO_STREAM)
    }

    private fun TestScope.newGateway(transport: FakeVoiceTransportV): VoiceGatewayConnection =
        DefaultVoiceGatewayConnection(
            scope = this,
            transportFactory = { transport },
            sleep = { awaitCancellation() },
        )

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) yield()
        }
    }
}
