package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA bindings for Network.framework — the sandbox-compatible UDP transport
 * Apple recommends for Mac App Store apps. Maps 1:1 to the iOS implementation
 * in `:shared:voice-codec` `IosVoiceUdpTransport.kt` (FP-6).
 *
 * All callbacks fire on the serial dispatch queue installed via
 * [nw_connection_set_queue]. Each Apple block parameter is forged via
 * [AppleBlock.forge] at the call site so the JNA Callback's `invoke` matches
 * Apple's documented block signature.
 */
internal interface Network : Library {

    interface StateChangedHandler : Callback {
        fun invoke(blockSelf: Pointer?, state: Int, error: Pointer?)
    }

    interface SendCompletion : Callback {
        fun invoke(blockSelf: Pointer?, error: Pointer?)
    }

    interface ReceiveCompletion : Callback {
        fun invoke(
            blockSelf: Pointer?,
            content: Pointer?,
            context: Pointer?,
            isComplete: Byte,
            error: Pointer?,
        )
    }

    fun nw_parameters_create_secure_udp(tlsOptions: Pointer?, udpOptions: Pointer?): Pointer
    fun nw_parameters_set_local_endpoint(parameters: Pointer?, localEndpoint: Pointer?)

    fun nw_endpoint_create_host(host: String, port: String): Pointer

    fun nw_connection_create(endpoint: Pointer?, parameters: Pointer?): Pointer?
    fun nw_connection_start(connection: Pointer?)
    fun nw_connection_cancel(connection: Pointer?)
    fun nw_connection_set_queue(connection: Pointer?, queue: Pointer?)
    fun nw_connection_set_state_changed_handler(connection: Pointer?, handlerBlock: Pointer?)

    fun nw_connection_send(
        connection: Pointer?,
        content: Pointer?,
        context: Pointer?,
        isComplete: Byte,
        completionBlock: Pointer?,
    )

    fun nw_connection_receive_message(connection: Pointer?, completionBlock: Pointer?)

    fun nw_error_get_error_code(error: Pointer?): Int
    fun nw_error_get_error_domain(error: Pointer?): Int

    fun nw_release(obj: Pointer?)

    companion object {
        val INSTANCE: Network by lazy { Native.load("Network", Network::class.java) }

        // nw_connection_state_t values per <Network/connection.h>.
        const val STATE_INVALID: Int = 0
        const val STATE_WAITING: Int = 1
        const val STATE_PREPARING: Int = 2
        const val STATE_READY: Int = 3
        const val STATE_FAILED: Int = 4
        const val STATE_CANCELLED: Int = 5

        /** `NW_PARAMETERS_DISABLE_PROTOCOL` — sentinel meaning "do not use this protocol slot". */
        val DISABLE_PROTOCOL: Pointer by lazy {
            com.sun.jna.NativeLibrary.getInstance("Network")
                .getGlobalVariableAddress("_nw_parameters_configure_protocol_disable")
        }

        /** `NW_PARAMETERS_DEFAULT_CONFIGURATION` — sentinel meaning "use Apple's default protocol config". */
        val DEFAULT_CONFIGURATION: Pointer by lazy {
            com.sun.jna.NativeLibrary.getInstance("Network")
                .getGlobalVariableAddress("_nw_parameters_configure_protocol_default_configuration")
        }

        /** `_nw_content_context_default_message` — singleton default-message context. */
        val DEFAULT_MESSAGE_CONTEXT: Pointer by lazy {
            com.sun.jna.NativeLibrary.getInstance("Network")
                .getGlobalVariableAddress("_nw_content_context_default_message")
        }

        /** Capacity of the incoming buffered channel; aligned with iOS impl + Discord voice rate. */
        const val INCOMING_BUFFER_CAPACITY: Int = 64
    }
}
