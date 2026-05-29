@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)

package dev.puklic.voice.codec.transport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.NW_PARAMETERS_DISABLE_PROTOCOL
import platform.Network._nw_content_context_default_message
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive_message
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_invalid
import platform.Network.nw_connection_state_preparing
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_t
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_create_secure_udp
import platform.Network.nw_parameters_set_local_endpoint
import platform.Network.nw_parameters_t
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_attr_t
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Apple Network.framework backed implementation of [VoiceUdpTransport].
 *
 * Each instance owns:
 *
 * - One `nw_connection_t` (created with [nw_parameters_create_secure_udp]
 *   passing [NW_PARAMETERS_DISABLE_PROTOCOL] for the TLS slot, leaving
 *   pure UDP).
 * - One serial dispatch queue used by Network.framework for all state,
 *   send, and receive callbacks. Serial = ordered = lock-free internal
 *   state.
 * - One `Channel<ByteArray>(Channel.BUFFERED)` bridging Network.framework
 *   receive callbacks to the [incoming] cold flow consumer.
 * - One [CoroutineScope] tied to [close] for cancellation hygiene.
 *
 * Connection state model (permanent contract, not a placeholder):
 *
 * - `.setup` / `.preparing` — readiness deferred unresolved; send callers suspend.
 * - `.ready` — readiness deferred resolved successfully; send + receive proceed.
 * - `.waiting(error)` — Apple says "may recover"; we keep waiting. Caller-visible
 *   only as continued suspension of send. A future iteration may add a
 *   watchdog timeout; the current behaviour is the documented
 *   Network.framework contract.
 * - `.failed(error)` — readiness deferred resolved with
 *   [VoiceUdpTransportException]; incoming channel closed with the same
 *   cause; transport marked closed.
 * - `.cancelled` — terminal; readiness resolved with [CancellationException]
 *   if still pending; incoming channel closed normally.
 *
 * @param remote the Discord voice / screenshare UDP endpoint
 * @param localBind optional specific local interface to bind to. When
 *   `null`, Network.framework chooses an ephemeral local port.
 */
public class IosVoiceUdpTransport internal constructor(
    private val remote: Endpoint,
    localBind: Endpoint?,
) : VoiceUdpTransport {

    private val queue: dispatch_queue_t = dispatch_queue_create(
        QUEUE_LABEL,
        null as dispatch_queue_attr_t,
    )

    private val connection: nw_connection_t = run {
        val params: nw_parameters_t = nw_parameters_create_secure_udp(
            NW_PARAMETERS_DISABLE_PROTOCOL,
            NW_PARAMETERS_DEFAULT_CONFIGURATION,
        ) ?: throw VoiceUdpTransportException("nw_parameters_create_secure_udp returned null")
        if (localBind != null) {
            val localEndpoint = nw_endpoint_create_host(localBind.host, localBind.port.toString())
            nw_parameters_set_local_endpoint(params, localEndpoint)
        }
        val remoteEndpoint = nw_endpoint_create_host(remote.host, remote.port.toString())
        nw_connection_create(remoteEndpoint, params)
            ?: throw VoiceUdpTransportException("nw_connection_create returned null for $remote")
    }

    private val readiness: CompletableDeferred<Unit> = CompletableDeferred()
    private val incomingChannel: Channel<ByteArray> = Channel(Channel.BUFFERED)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val receiveArmed = AtomicBoolean(false)

    init {
        nw_connection_set_state_changed_handler(connection) { state, error ->
            handleStateChange(state, error)
        }
        nw_connection_set_queue(connection, queue)
    }

    override suspend fun send(packet: ByteArray) {
        if (closed.load()) throw VoiceUdpTransportException("Transport is closed")
        ensureStarted()
        readiness.await()
        // dispatch_data_create copies the bytes into a Network.framework-owned
        // buffer (DISPATCH_DATA_DESTRUCTOR_DEFAULT signals a copy), so the
        // source ByteArray can move / be GC'd after this call returns.
        val data: dispatch_data_t = if (packet.isEmpty()) {
            dispatch_data_create(
                buffer = null,
                size = 0u,
                queue = dispatch_get_main_queue(),
                // Null destructor instructs dispatch to copy the buffer
                // immediately into its own storage (DISPATCH_DATA_DESTRUCTOR_DEFAULT
                // semantics). The source ByteArray pin can be released as
                // soon as dispatch_data_create returns.
                destructor = null,
            )
        } else {
            packet.usePinned { pinned ->
                dispatch_data_create(
                    buffer = pinned.addressOf(0),
                    size = packet.size.toULong(),
                    queue = dispatch_get_main_queue(),
                    // Null destructor instructs dispatch to copy the buffer
                // immediately into its own storage (DISPATCH_DATA_DESTRUCTOR_DEFAULT
                // semantics). The source ByteArray pin can be released as
                // soon as dispatch_data_create returns.
                destructor = null,
                )
            }
        }
        suspendCancellableCoroutine<Unit> { cont ->
            nw_connection_send(
                connection = connection,
                content = data,
                context = _nw_content_context_default_message,
                is_complete = true,
                completion = { error: nw_error_t? ->
                    if (error != null) {
                        cont.resumeWithException(
                            VoiceUdpTransportException("nw_connection_send failed: ${describe(error)}"),
                        )
                    } else {
                        cont.resume(Unit)
                    }
                },
            )
        }
    }

    override val incoming: Flow<ByteArray> = flow {
        for (datagram in incomingChannel) {
            emit(datagram)
        }
    }.onStart {
        // Arm the receive loop on first collect. ensureStarted() transitions
        // the connection to a started state; the receive loop must also be
        // armed exactly once. Both guards are idempotent.
        ensureStarted()
        armReceiveOnce()
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        nw_connection_cancel(connection)
        // The .cancelled state will close incomingChannel via
        // handleStateChange; in case the connection never reached a
        // started state we close proactively as a safety net (idempotent).
        incomingChannel.close()
        if (!readiness.isCompleted) {
            readiness.completeExceptionally(CancellationException("Transport closed"))
        }
        scope.cancel("Transport closed")
    }

    // --- internals -----------------------------------------------------

    private fun ensureStarted() {
        if (started.compareAndSet(expectedValue = false, newValue = true)) {
            nw_connection_start(connection)
        }
    }

    private fun armReceiveOnce() {
        if (receiveArmed.compareAndSet(expectedValue = false, newValue = true)) {
            scheduleReceive()
        }
    }

    private fun scheduleReceive() {
        if (closed.load()) return
        nw_connection_receive_message(connection) { content, _, _, error ->
            onReceiveCompletion(content, error)
        }
    }

    private fun onReceiveCompletion(content: dispatch_data_t?, error: nw_error_t?) {
        if (closed.load()) return
        if (error != null) {
            incomingChannel.close(
                VoiceUdpTransportException("nw_connection_receive_message failed: ${describe(error)}"),
            )
            return
        }
        if (content != null && dispatch_data_get_size(content) > 0u) {
            val bytes = content.toByteArray()
            val sendResult = incomingChannel.trySend(bytes)
            // For a BUFFERED channel (capacity 64), trySend fails only if
            // the consumer has fallen behind by 64+ datagrams. At Discord
            // voice rates (~50 pps) that is >1 s of backlog and indicates
            // a dead consumer. Surface as a channel close-with-cause so
            // the Flow collector terminates loudly rather than silently
            // dropping audio.
            if (sendResult.isFailure && !sendResult.isClosed) {
                incomingChannel.close(
                    VoiceUdpTransportException(
                        "Incoming channel full ($INCOMING_BUFFER_HINT); consumer fell behind",
                    ),
                )
                return
            }
        }
        // Re-arm the receive loop. Network.framework's receive_message is
        // strictly one-shot; the next datagram requires another call.
        scheduleReceive()
    }

    private fun handleStateChange(state: nw_connection_state_t, error: nw_error_t?) {
        when (state) {
            nw_connection_state_invalid,
            nw_connection_state_preparing,
            nw_connection_state_waiting -> {
                // No-op: readiness deferred remains unresolved; send
                // suspends. .waiting is Apple's "may recover" state; we
                // honour it by continuing to wait. A timeout watchdog
                // is a future enhancement, not a stub.
            }
            nw_connection_state_ready -> {
                if (!readiness.isCompleted) readiness.complete(Unit)
            }
            nw_connection_state_failed -> {
                val cause = VoiceUdpTransportException(
                    "nw_connection failed: ${error?.let { describe(it) } ?: "unknown"}",
                )
                if (!readiness.isCompleted) readiness.completeExceptionally(cause)
                incomingChannel.close(cause)
                closed.store(true)
            }
            nw_connection_state_cancelled -> {
                if (!readiness.isCompleted) {
                    readiness.completeExceptionally(CancellationException("Connection cancelled"))
                }
                incomingChannel.close()
                closed.store(true)
            }
            else -> {
                // Forward-compat: unknown state values are treated as
                // transient; no readiness resolution.
            }
        }
    }

    private companion object {
        const val QUEUE_LABEL = "dev.puklic.voice.udp"
        const val INCOMING_BUFFER_HINT = "buffer=Channel.BUFFERED"
    }
}

/**
 * Apple-side error thrown by [IosVoiceUdpTransport] when Network.framework
 * surfaces a send, receive, or connection failure. Wraps the `nw_error_t`
 * domain + code so callers can handle voice transport faults uniformly
 * across JVM and iOS.
 */
public class VoiceUdpTransportException(message: String) : RuntimeException(message)

// --- Network.framework helpers --------------------------------------------

@OptIn(ExperimentalForeignApi::class)
private fun describe(error: nw_error_t): String {
    val domain = nw_error_get_error_domain(error)
    val code = nw_error_get_error_code(error)
    return "domain=$domain code=$code"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun dispatch_data_t.toByteArray(): ByteArray {
    val size = dispatch_data_get_size(this).toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    var offset = 0
    // dispatch_data may be composed of multiple non-contiguous regions; the
    // applier is invoked once per contiguous region. Concatenate them in
    // order. Returning `true` from the applier continues iteration.
    dispatch_data_apply(this) { _, _, buffer, length ->
        val len = length.toInt()
        if (buffer != null && len > 0) {
            val src: CPointer<kotlinx.cinterop.ByteVar> = buffer.reinterpret()
            out.usePinned { pinnedOut ->
                platform.posix.memcpy(
                    pinnedOut.addressOf(offset),
                    src,
                    len.toULong(),
                )
            }
            offset += len
        }
        true
    }
    return out
}
