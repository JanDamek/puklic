package dev.puklic.desktop.macappstore.transport

import dev.puklic.voice.codec.transport.Endpoint
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.codec.transport.VoiceUdpTransportFactory

/**
 * Factory for [JnaNwConnectionUdpTransport]. The Mac App Store DependencyGraph
 * (FP-14d) wires this in where the GPL JVM build uses
 * `JvmVoiceUdpTransportFactory`.
 */
public object JnaNwConnectionUdpTransportFactory : VoiceUdpTransportFactory {
    public override fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport =
        JnaNwConnectionUdpTransport(remote, localBind)
}
