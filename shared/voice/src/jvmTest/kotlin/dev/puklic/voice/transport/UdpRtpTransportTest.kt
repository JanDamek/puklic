package dev.puklic.voice.transport

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class UdpRtpTransportTest {

    @Test
    fun `two loopback transports can send and receive a packet`() = runBlocking {
        val a = newUdpRtpTransport()
        val b = newUdpRtpTransport()
        try {
            val portA = a.bind()
            val portB = b.bind()

            a.connect("127.0.0.1", portB)
            b.connect("127.0.0.1", portA)

            val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
            val received = async {
                withTimeout(5_000) { b.receive() }
            }
            a.send(payload)
            received.await().toList() shouldBe payload.toList()
        } finally {
            a.close()
            b.close()
        }
    }
}
