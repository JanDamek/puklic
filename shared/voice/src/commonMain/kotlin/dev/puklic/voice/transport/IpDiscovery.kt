package dev.puklic.voice.transport

import kotlinx.coroutines.withTimeout

/**
 * Discord voice IP discovery handshake.
 *
 * Source of truth: `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §6.
 *
 * Packet layout (74 bytes, big-endian):
 * ```
 * offset  size  field
 * 0       2     type (0x0001 request / 0x0002 response)
 * 2       2     length (always 70)
 * 4       4     ssrc
 * 8       64    address (zero-padded ASCII)
 * 72      2     port
 * ```
 */
internal object IpDiscovery {

    private const val PACKET_SIZE: Int = 74
    private const val PAYLOAD_LENGTH: Int = 70
    private const val ADDRESS_OFFSET: Int = 8
    private const val ADDRESS_SIZE: Int = 64
    private const val PORT_OFFSET: Int = 72
    private const val TYPE_REQUEST: Int = 0x0001
    private const val TYPE_RESPONSE: Int = 0x0002

    internal data class Result(val address: String, val port: Int)

    fun buildRequest(ssrc: Int): ByteArray {
        val pkt = ByteArray(PACKET_SIZE)
        writeU16BE(pkt, 0, TYPE_REQUEST)
        writeU16BE(pkt, 2, PAYLOAD_LENGTH)
        writeU32BE(pkt, 4, ssrc)
        // bytes 8..73 already zero (address + port)
        return pkt
    }

    fun parseResponse(bytes: ByteArray): Result {
        require(bytes.size == PACKET_SIZE) {
            "ip discovery response must be $PACKET_SIZE bytes, got ${bytes.size}"
        }
        val type = readU16BE(bytes, 0)
        require(type == TYPE_RESPONSE) {
            "ip discovery response type must be 0x%04x, got 0x%04x".format(TYPE_RESPONSE, type)
        }
        val end = (ADDRESS_OFFSET until ADDRESS_OFFSET + ADDRESS_SIZE)
            .firstOrNull { bytes[it] == 0.toByte() } ?: (ADDRESS_OFFSET + ADDRESS_SIZE)
        val address = bytes.decodeToString(ADDRESS_OFFSET, end)
        val port = readU16BE(bytes, PORT_OFFSET)
        return Result(address, port)
    }

    private fun writeU16BE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32BE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readU16BE(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)
}

/**
 * UDP RTP transport abstraction. JVM `actual` uses `java.net.DatagramSocket`.
 *
 * Single-threaded blocking I/O per direction is fine for voice — 50 packets/sec.
 */
internal interface UdpRtpTransport {
    suspend fun bind(): Int
    suspend fun connect(host: String, port: Int)
    suspend fun send(packet: ByteArray)
    suspend fun receive(): ByteArray
    fun close()
}

internal expect fun newUdpRtpTransport(): UdpRtpTransport

/**
 * Run the 74-byte IP discovery handshake against the connected voice server.
 * Sends a request and awaits the response, returning the discovered external (address, port).
 */
internal suspend fun UdpRtpTransport.discoverIp(ssrc: Int, timeoutMs: Long = 5_000): IpDiscovery.Result {
    send(IpDiscovery.buildRequest(ssrc))
    val response = withTimeout(timeoutMs) { receive() }
    return IpDiscovery.parseResponse(response)
}
