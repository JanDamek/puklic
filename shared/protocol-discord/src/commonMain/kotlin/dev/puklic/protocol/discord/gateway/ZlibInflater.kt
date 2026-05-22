package dev.puklic.protocol.discord.gateway

/**
 * Incremental zlib-stream decompressor.
 *
 * Discord sends a continuous zlib stream over the gateway when `compress=zlib-stream` is
 * present in the connect URL. Each logical message terminates with the Z_SYNC_FLUSH marker
 * `\x00\x00\xff\xff`. Implementations maintain a single inflater per connection and emit
 * one decompressed UTF-8 string per completed message.
 *
 * Implementations must be tolerant of byte-level framing — a single `feed` call may contain
 * zero, one, or many complete messages, and partial messages must be buffered until the
 * next call. Implementations are NOT thread-safe; each gateway connection owns one inflater.
 */
internal interface ZlibInflater {
    fun feed(bytes: ByteArray): List<String>

    fun reset()
}
