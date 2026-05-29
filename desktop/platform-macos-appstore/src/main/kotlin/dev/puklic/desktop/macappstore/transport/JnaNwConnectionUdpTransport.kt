package dev.puklic.desktop.macappstore.transport

import com.sun.jna.Memory
import com.sun.jna.Pointer
import dev.puklic.desktop.macappstore.bridge.AppleBlock
import dev.puklic.desktop.macappstore.bridge.Dispatch
import dev.puklic.desktop.macappstore.bridge.Network
import dev.puklic.voice.codec.transport.Endpoint
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.codec.transport.VoiceUdpTransportFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mac App Store variant of [VoiceUdpTransport] backed by Apple's
 * Network.framework `nw_connection_t`. Behaviour mirrors the iOS impl
 * [`IosVoiceUdpTransport`][1] — same connection state machine, same one-shot
 * receive re-arm, same buffered channel + cold flow surface.
 *
 * Each instance owns:
 *  - one `nw_connection_t` created with `nw_parameters_create_secure_udp(disable, default)`
 *    (pure UDP — no TLS slot);
 *  - one serial libdispatch queue used by Network.framework for every callback;
 *  - one `Channel<ByteArray>(BUFFERED)` bridging receive callbacks to the cold
 *    flow consumer;
 *  - one [CoroutineScope] tied to [close] for cancellation hygiene.
 *
 * The block-typed Network.framework APIs (`state_changed_handler`,
 * `nw_connection_send` completion, `nw_connection_receive_message` completion)
 * are wrapped by [AppleBlock]-forged global blocks. Each forged block's
 * keep-alive is held in a field so the JNA Callback trampoline is GC-safe
 * for the connection's lifetime.
 *
 * [1]: shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/transport/IosVoiceUdpTransport.kt
 */
public class JnaNwConnectionUdpTransport internal constructor(
    private val remote: Endpoint,
    localBind: Endpoint?,
) : VoiceUdpTransport {

    private val nw: Network = Network.INSTANCE
    private val dispatch: Dispatch = Dispatch.INSTANCE

    private val queue: Pointer = dispatch.dispatch_queue_create(QUEUE_LABEL, null)

    private val readiness: CompletableDeferred<Unit> = CompletableDeferred()
    private val incomingChannel: Channel<ByteArray> = Channel(Network.INCOMING_BUFFER_CAPACITY)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val receiveArmed = AtomicBoolean(false)

    // Held to keep the JNA callback trampolines + heap-backed block literals alive
    // for the entire lifetime of the connection.
    private val stateBlock: AppleBlock.Keepalive
    private val connection: Pointer

    init {
        val params: Pointer = nw.nw_parameters_create_secure_udp(
            Network.DISABLE_PROTOCOL,
            Network.DEFAULT_CONFIGURATION,
        )
        if (localBind != null) {
            val localEndpoint = nw.nw_endpoint_create_host(localBind.host, localBind.port.toString())
            nw.nw_parameters_set_local_endpoint(params, localEndpoint)
        }
        val remoteEndpoint = nw.nw_endpoint_create_host(remote.host, remote.port.toString())
        connection = nw.nw_connection_create(remoteEndpoint, params)
            ?: throw VoiceUdpTransportException("nw_connection_create returned null for $remote")

        val stateHandler = object : Network.StateChangedHandler {
            override fun invoke(blockSelf: Pointer?, state: Int, error: Pointer?) {
                handleStateChange(state, error)
            }
        }
        stateBlock = AppleBlock.forge(stateHandler)
        nw.nw_connection_set_state_changed_handler(connection, stateBlock.block)
        nw.nw_connection_set_queue(connection, queue)
    }

    override suspend fun send(packet: ByteArray) {
        if (closed.get()) throw VoiceUdpTransportException("Transport is closed")
        ensureStarted()
        readiness.await()

        val data = createDispatchData(packet)
        val sendKeepalive = arrayOfNulls<AppleBlock.Keepalive>(1)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val completion = object : Network.SendCompletion {
                    override fun invoke(blockSelf: Pointer?, error: Pointer?) {
                        if (error != null) {
                            cont.resumeWithException(
                                VoiceUdpTransportException(
                                    "nw_connection_send failed: ${describe(error)}",
                                ),
                            )
                        } else {
                            cont.resume(Unit)
                        }
                    }
                }
                val keepalive = AppleBlock.forge(completion)
                sendKeepalive[0] = keepalive
                nw.nw_connection_send(
                    connection = connection,
                    content = data,
                    context = Network.DEFAULT_MESSAGE_CONTEXT,
                    isComplete = 1,
                    completionBlock = keepalive.block,
                )
            }
        } finally {
            // Keep `sendKeepalive` referenced to the end of this scope: by the time
            // suspendCancellableCoroutine returns, the completion block has fired.
            sendKeepalive[0] = null
            dispatch.dispatch_release(data)
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        for (datagram in incomingChannel) emit(datagram)
    }.onStart {
        ensureStarted()
        armReceiveOnce()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        nw.nw_connection_cancel(connection)
        incomingChannel.close()
        if (!readiness.isCompleted) {
            readiness.completeExceptionally(CancellationException("Transport closed"))
        }
        scope.cancel("Transport closed")
    }

    // --- internals -----------------------------------------------------

    private fun ensureStarted() {
        if (started.compareAndSet(false, true)) nw.nw_connection_start(connection)
    }

    private fun armReceiveOnce() {
        if (receiveArmed.compareAndSet(false, true)) scheduleReceive()
    }

    /** Re-armed after every successful receive; Apple's API is strictly one-shot. */
    private fun scheduleReceive() {
        if (closed.get()) return
        val receiver = object : Network.ReceiveCompletion {
            override fun invoke(
                blockSelf: Pointer?,
                content: Pointer?,
                context: Pointer?,
                isComplete: Byte,
                error: Pointer?,
            ) {
                onReceiveCompletion(content, error)
            }
        }
        val keepalive = AppleBlock.forge(receiver)
        // Receive completion is fire-once; the block keepalive can be released as
        // soon as `onReceiveCompletion` returns, but we re-arm immediately on the
        // same code path so a fresh keepalive is constructed for the next datagram.
        receiveKeepalive = keepalive
        nw.nw_connection_receive_message(connection, keepalive.block)
    }

    /** Strong reference to the currently-armed receive block; replaced on each re-arm. */
    @Volatile
    private var receiveKeepalive: AppleBlock.Keepalive? = null

    private fun onReceiveCompletion(content: Pointer?, error: Pointer?) {
        if (closed.get()) return
        if (error != null) {
            incomingChannel.close(
                VoiceUdpTransportException("nw_connection_receive_message failed: ${describe(error)}"),
            )
            return
        }
        if (content != null && dispatch.dispatch_data_get_size(content) > 0L) {
            val bytes = readDispatchData(content)
            val sendResult = incomingChannel.trySend(bytes)
            if (sendResult.isFailure && !sendResult.isClosed) {
                incomingChannel.close(
                    VoiceUdpTransportException(
                        "Incoming channel full (capacity=${Network.INCOMING_BUFFER_CAPACITY}); " +
                            "consumer fell behind",
                    ),
                )
                return
            }
        }
        scheduleReceive()
    }

    private fun handleStateChange(state: Int, error: Pointer?) {
        when (state) {
            Network.STATE_INVALID,
            Network.STATE_PREPARING,
            Network.STATE_WAITING -> {
                // Apple's "may recover" zone — keep waiting; send remains suspended.
            }
            Network.STATE_READY -> {
                if (!readiness.isCompleted) readiness.complete(Unit)
            }
            Network.STATE_FAILED -> {
                val cause = VoiceUdpTransportException(
                    "nw_connection failed: ${error?.let { describe(it) } ?: "unknown"}",
                )
                if (!readiness.isCompleted) readiness.completeExceptionally(cause)
                incomingChannel.close(cause)
                closed.set(true)
            }
            Network.STATE_CANCELLED -> {
                if (!readiness.isCompleted) {
                    readiness.completeExceptionally(CancellationException("Connection cancelled"))
                }
                incomingChannel.close()
                closed.set(true)
            }
        }
    }

    private fun createDispatchData(packet: ByteArray): Pointer {
        if (packet.isEmpty()) {
            return dispatch.dispatch_data_create(
                buffer = null,
                size = 0L,
                queue = dispatch.dispatch_get_main_queue(),
                destructor = null,
            )
        }
        // Copy bytes into a JNA Memory so dispatch_data_create can copy them
        // immediately (NULL destructor == DISPATCH_DATA_DESTRUCTOR_DEFAULT).
        val mem = Memory(packet.size.toLong()).apply { write(0L, packet, 0, packet.size) }
        return dispatch.dispatch_data_create(
            buffer = mem,
            size = packet.size.toLong(),
            queue = dispatch.dispatch_get_main_queue(),
            destructor = null,
        )
    }

    private fun readDispatchData(content: Pointer): ByteArray {
        val size = dispatch.dispatch_data_get_size(content).toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        var written = 0
        val applier = object : Dispatch.DataApplier {
            override fun invoke(region: Pointer?, offset: Long, buffer: Pointer?, regionSize: Long): Byte {
                val len = regionSize.toInt()
                if (buffer != null && len > 0) {
                    buffer.read(0L, out, written, len)
                    written += len
                }
                return 1
            }
        }
        dispatch.dispatch_data_apply(content, applier)
        return out
    }

    private fun describe(error: Pointer): String {
        val domain = nw.nw_error_get_error_domain(error)
        val code = nw.nw_error_get_error_code(error)
        return "domain=$domain code=$code"
    }

    private companion object {
        const val QUEUE_LABEL = "dev.puklic.macappstore.voice.udp"
    }
}

/** Apple-side error thrown by [JnaNwConnectionUdpTransport] for Network.framework failures. */
public class VoiceUdpTransportException(message: String) : RuntimeException(message)
