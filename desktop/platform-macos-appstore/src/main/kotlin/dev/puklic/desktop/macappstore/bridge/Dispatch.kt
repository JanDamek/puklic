package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA bindings for libdispatch (Grand Central Dispatch). Used by the
 * Network.framework transport to host the serial callback queue and to wrap
 * raw byte buffers as `dispatch_data_t` values for `nw_connection_send`.
 */
internal interface Dispatch : Library {

    /** `dispatch_block_t` — argument-less block; we only use this for queue create attrs. */
    interface DataApplier : Callback {
        /**
         * `bool (^applier)(dispatch_data_t region, size_t offset, const void *buffer, size_t size)`.
         * Returning a non-zero byte continues iteration; zero stops.
         */
        fun invoke(region: Pointer?, offset: Long, buffer: Pointer?, size: Long): Byte
    }

    fun dispatch_queue_create(label: String?, attr: Pointer?): Pointer
    fun dispatch_get_main_queue(): Pointer

    fun dispatch_data_create(
        buffer: Pointer?,
        size: Long,
        queue: Pointer?,
        destructor: Pointer?,
    ): Pointer

    fun dispatch_data_get_size(data: Pointer?): Long
    fun dispatch_data_apply(data: Pointer?, applier: DataApplier): Byte

    fun dispatch_release(obj: Pointer?)

    companion object {
        val INSTANCE: Dispatch by lazy { Native.load("System", Dispatch::class.java) }
    }
}
