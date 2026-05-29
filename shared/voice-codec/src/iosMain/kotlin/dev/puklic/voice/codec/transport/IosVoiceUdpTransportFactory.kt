package dev.puklic.voice.codec.transport

/**
 * iOS / macOS-App-Store factory for [VoiceUdpTransport], producing
 * [IosVoiceUdpTransport] instances backed by Apple Network.framework
 * (`nw_connection_t` + UDP `nw_parameters`).
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-29-fp6-ios-network-framework.md`.
 */
public object IosVoiceUdpTransportFactory : VoiceUdpTransportFactory {
    override fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport =
        IosVoiceUdpTransport(remote = remote, localBind = localBind)
}
