package dev.puklic.voice.codec.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Loopback round-trip smoke test for [IosVoiceUdpTransport].
 *
 * The test wires a "server" transport bound to `127.0.0.1` on a fixed port,
 * and a "client" transport pointing at it. The client sends a known byte
 * pattern; the server's [VoiceUdpTransport.incoming] flow must emit the
 * same payload within a short timeout.
 *
 * On simulator + Mac host this is a real Network.framework loopback
 * exercise. On compile-only CI (no simulator), the build phase still
 * validates Kotlin/Native cinterop symbol resolution, which is the
 * primary FP-6 acceptance gate per the architect report.
 *
 * If the chosen test port is already in use, Network.framework surfaces
 * a `.failed` state — the server's [VoiceUdpTransport.incoming] flow then
 * propagates the failure as an exception, which fails the test loudly
 * rather than silently skipping.
 */
class IosVoiceUdpTransportTest {

    @Test
    fun loopback_round_trip_delivers_exact_bytes() = runBlocking {
        val serverBind = Endpoint(host = LOOPBACK, port = TEST_PORT)
        val server = IosVoiceUdpTransportFactory.create(
            remote = Endpoint(host = LOOPBACK, port = TEST_PORT),
            localBind = serverBind,
        )
        val client = IosVoiceUdpTransportFactory.create(
            remote = serverBind,
            localBind = null,
        )
        try {
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x7F, -0x80, 0x55, -0x2A)
            val received = withTimeout(RECEIVE_TIMEOUT_MS) {
                coroutineScope {
                    val deferred = async { server.incoming.first() }
                    // Let the server's onStart arm the receive loop before
                    // we send; Network.framework dispatches handler setup
                    // onto the per-connection serial queue.
                    yield()
                    client.send(payload)
                    deferred.await()
                }
            }
            assertContentEquals(payload, received)
        } finally {
            client.close()
            server.close()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        // Fixed loopback port for the test. Range chosen above the usual
        // ephemeral range to reduce collision likelihood on dev machines.
        const val TEST_PORT = 55554

        const val RECEIVE_TIMEOUT_MS = 5_000L
    }
}
