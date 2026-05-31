package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec

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
 *
 * Moved here from `:shared:voice/commonMain` 2026-05-29 (FP-14h-2a) per architect report
 * `2026-05-29-fp14h-1-v2-voice-gateway-redesign.md` §9.2 row 6. The legacy
 * `UdpRtpTransport` interface + `expect newUdpRtpTransport()` + the `discoverIp` extension
 * on it were deleted in FP-14h-2b (issue #66); the FP-3 [VoiceUdpTransport]-typed
 * `discoverIp` lives in `IpDiscoveryRunner.kt`.
 */
@PuklicVoiceCodec
public object IpDiscovery {

    private const val PACKET_SIZE: Int = 74
    private const val PAYLOAD_LENGTH: Int = 70
    private const val ADDRESS_OFFSET: Int = 8
    private const val ADDRESS_SIZE: Int = 64
    private const val PORT_OFFSET: Int = 72
    private const val TYPE_REQUEST: Int = 0x0001
    private const val TYPE_RESPONSE: Int = 0x0002

    public data class Result(val address: String, val port: Int)

    public fun buildRequest(ssrc: Int): ByteArray {
        val pkt = ByteArray(PACKET_SIZE)
        writeU16BE(pkt, 0, TYPE_REQUEST)
        writeU16BE(pkt, 2, PAYLOAD_LENGTH)
        writeU32BE(pkt, 4, ssrc)
        // bytes 8..73 already zero (address + port)
        return pkt
    }

    public fun parseResponse(bytes: ByteArray): Result {
        require(bytes.size == PACKET_SIZE) {
            "ip discovery response must be $PACKET_SIZE bytes, got ${bytes.size}"
        }
        val type = readU16BE(bytes, 0)
        require(type == TYPE_RESPONSE) {
            "ip discovery response type must be 0x${TYPE_RESPONSE.toHex4()}, got 0x${type.toHex4()}"
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

    private fun Int.toHex4(): String = (this and 0xFFFF).toString(16).padStart(4, '0')
}
