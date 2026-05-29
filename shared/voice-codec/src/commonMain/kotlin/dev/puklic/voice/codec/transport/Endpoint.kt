package dev.puklic.voice.codec.transport

/**
 * A `(host, port)` pair identifying a UDP endpoint.
 *
 * Used by [VoiceUdpTransportFactory] to describe the remote Discord voice / screenshare
 * server and (optionally) a specific local interface to bind to.
 */
public data class Endpoint(val host: String, val port: Int)
