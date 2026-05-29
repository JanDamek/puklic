package dev.puklic.voice.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * JVM actual: blocking `DatagramSocket` driven from `Dispatchers.IO`.
 *
 * The buffer is sized for the largest expected packet — Opus at 64 kbps fits well below 2 KiB,
 * but we allow a comfortable headroom for jitter buffer slack and silence frames.
 */
private const val RECEIVE_BUFFER_BYTES: Int = 2048

public actual fun newUdpRtpTransport(): UdpRtpTransport = JvmUdpRtpTransport()

private class JvmUdpRtpTransport : UdpRtpTransport {
    private var socket: DatagramSocket? = null
    private var remote: InetSocketAddress? = null

    override suspend fun bind(): Int = withContext(Dispatchers.IO) {
        check(socket == null) { "transport already bound" }
        val s = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        socket = s
        s.localPort
    }

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        remote = InetSocketAddress(InetAddress.getByName(host), port)
    }

    override suspend fun send(packet: ByteArray) = withContext(Dispatchers.IO) {
        val s = checkNotNull(socket) { "transport not bound" }
        val r = checkNotNull(remote) { "transport not connected" }
        s.send(DatagramPacket(packet, packet.size, r))
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val s = checkNotNull(socket) { "transport not bound" }
        val buf = ByteArray(RECEIVE_BUFFER_BYTES)
        val dp = DatagramPacket(buf, buf.size)
        s.receive(dp)
        buf.copyOf(dp.length)
    }

    override fun close() {
        socket?.close()
        socket = null
        remote = null
    }
}
