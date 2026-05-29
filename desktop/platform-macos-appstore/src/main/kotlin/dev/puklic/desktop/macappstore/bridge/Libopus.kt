package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * JNA binding to the bundled `libopus.dylib` (BSD-3-Clause libopus 1.5.2 — same
 * binary as the iOS `Opus.xcframework` macOS slice, re-linked into a dylib by
 * `dist/apple/build-libopus-dylib-from-xcframework.sh`).
 *
 * Loading strategy: `Native.load("opus", …)` resolves via JNA's standard
 * `jna.library.path` + `java.library.path` + `LD_LIBRARY_PATH` search. The
 * macAppStoreTest Gradle task prepends the committed
 * `desktop/platform-macos-appstore/libs/` directory to `jna.library.path`; the
 * packaged Mac App Store .app sets the same property to its
 * `Contents/Resources` directory at startup (FP-14d scope).
 *
 * libopus opaque structs are represented as JNA `Pointer` values (`opus_encoder_create`
 * / `opus_decoder_create` return malloc'd buffers; `opus_encoder_destroy` /
 * `opus_decoder_destroy` free them).
 *
 * CTL macros (`OPUS_SET_BITRATE` etc.) are not C functions — they expand to
 * `opus_encoder_ctl(enc, request, value)` with the request id as a varargs
 * integer. We bind a typed varargs C wrapper at the JNA level by declaring
 * `opus_encoder_ctl(Pointer, Int, Int)` and `opus_encoder_ctl(Pointer, Int)`
 * for setters returning ints.
 */
@Suppress("FunctionNaming")
internal interface Libopus : Library {

    fun opus_encoder_create(fs: Int, channels: Int, application: Int, error: IntByReference?): Pointer?
    fun opus_encoder_destroy(state: Pointer?)
    fun opus_encode(
        state: Pointer?,
        pcm: ShortArray,
        frameSize: Int,
        data: ByteArray,
        maxDataBytes: Int,
    ): Int

    fun opus_decoder_create(fs: Int, channels: Int, error: IntByReference?): Pointer?
    fun opus_decoder_destroy(state: Pointer?)
    fun opus_decode(
        state: Pointer?,
        data: ByteArray?,
        len: Int,
        pcm: ShortArray,
        frameSize: Int,
        decodeFec: Int,
    ): Int

    /** `opus_encoder_ctl(OpusEncoder*, int request, int value)` — setter form. */
    fun opus_encoder_ctl(state: Pointer?, request: Int, value: Int): Int

    fun opus_strerror(error: Int): String

    companion object {
        val INSTANCE: Libopus by lazy { Native.load("opus", Libopus::class.java) }

        // CTL request constants from <opus/opus_defines.h>.
        const val OPUS_SET_BITRATE_REQUEST: Int = 4002
        const val OPUS_SET_COMPLEXITY_REQUEST: Int = 4010
        const val OPUS_SET_INBAND_FEC_REQUEST: Int = 4012
        const val OPUS_SET_PACKET_LOSS_PERC_REQUEST: Int = 4014
        const val OPUS_SET_DTX_REQUEST: Int = 4016

        // OPUS_APPLICATION_* constants from <opus/opus_defines.h>.
        const val OPUS_APPLICATION_VOIP: Int = 2048
        const val OPUS_APPLICATION_AUDIO: Int = 2049
        const val OPUS_APPLICATION_RESTRICTED_LOWDELAY: Int = 2051
    }
}
