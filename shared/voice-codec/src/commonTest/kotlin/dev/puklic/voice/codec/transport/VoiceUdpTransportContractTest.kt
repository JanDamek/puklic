package dev.puklic.voice.codec.transport

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * Compile-only contract test for the FP-3 KMP UDP transport surface. Verifies that
 * [Endpoint] is constructible from commonMain, that [VoiceUdpTransport] /
 * [VoiceUdpTransportFactory] have the agreed shape, and that a minimal fake factory
 * compiles against the interfaces.
 *
 * No real socket is opened; that is exercised by `UdpRtpTransportTest` (JVM) and by the
 * iOS Network.framework actual tests landing in FP-6.
 */
class VoiceUdpTransportContractTest {

    @Test
    fun endpoint_isConstructibleFromCommon() {
        val ep = Endpoint(host = "voice.discord.gg", port = 50001)
        ep.host shouldBe "voice.discord.gg"
        ep.port shouldBe 50001
    }

    @Test
    fun endpoint_equalsRespectsHostAndPort() {
        val a = Endpoint("1.2.3.4", 50001)
        val b = Endpoint("1.2.3.4", 50001)
        val c = Endpoint("1.2.3.4", 50002)
        (a == b) shouldBe true
        a.hashCode() shouldBe b.hashCode()
        (a == c) shouldBe false
    }

    @Test
    fun factory_producesTransportImplementingInterface() {
        val factory: VoiceUdpTransportFactory = FakeFactory
        val transport: VoiceUdpTransport = factory.create(
            remote = Endpoint("127.0.0.1", 50000),
            localBind = null,
        )
        transport.close()
    }

    @Test
    fun factory_acceptsLocalBindOverride() {
        val factory: VoiceUdpTransportFactory = FakeFactory
        val transport: VoiceUdpTransport = factory.create(
            remote = Endpoint("127.0.0.1", 50000),
            localBind = Endpoint("192.168.1.10", 0),
        )
        transport.close()
    }

    private object FakeFactory : VoiceUdpTransportFactory {
        override fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport =
            FakeTransport
    }

    private object FakeTransport : VoiceUdpTransport {
        override suspend fun send(packet: ByteArray) = Unit
        override val incoming: Flow<ByteArray> = emptyFlow()
        override fun close() = Unit
    }
}
