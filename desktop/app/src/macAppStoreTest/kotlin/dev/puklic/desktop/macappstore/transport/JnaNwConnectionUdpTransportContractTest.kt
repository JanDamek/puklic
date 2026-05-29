package dev.puklic.desktop.macappstore.transport

import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.codec.transport.VoiceUdpTransportFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for the Mac App Store variant's JNA-backed NWConnection UDP
 * transport (Network.framework).
 *
 * FP-14b RED phase: impl classes do not exist yet — FP-14c lands them.
 *
 * Surface locked:
 *  1. `JnaNwConnectionUdpTransport` is loadable and implements
 *     [VoiceUdpTransport].
 *  2. `JnaNwConnectionUdpTransportFactory` is loadable and implements
 *     [VoiceUdpTransportFactory].
 *
 * Apple's Network.framework is the sandbox-compatible UDP path for the Mac
 * App Store build; raw `java.net.DatagramSocket` is permitted in sandbox
 * with `com.apple.security.network.client/server` entitlements but
 * Network.framework integrates with TCC and the system-level NEP. See
 * FP-14a §4.2.
 */
class JnaNwConnectionUdpTransportContractTest {

    private val transportFqn = "dev.puklic.desktop.macappstore.transport.JnaNwConnectionUdpTransport"
    private val factoryFqn = "dev.puklic.desktop.macappstore.transport.JnaNwConnectionUdpTransportFactory"

    @Test
    fun `JnaNwConnectionUdpTransport class is loadable on macAppStore classpath`() {
        val cls = Class.forName(transportFqn)
        assertTrue(
            VoiceUdpTransport::class.java.isAssignableFrom(cls),
            "$transportFqn must implement VoiceUdpTransport",
        )
    }

    @Test
    fun `JnaNwConnectionUdpTransportFactory class is loadable and is a VoiceUdpTransportFactory`() {
        val cls = Class.forName(factoryFqn)
        assertTrue(
            VoiceUdpTransportFactory::class.java.isAssignableFrom(cls),
            "$factoryFqn must implement VoiceUdpTransportFactory",
        )
    }
}
