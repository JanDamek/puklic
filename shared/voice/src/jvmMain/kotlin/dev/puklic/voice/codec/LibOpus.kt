package dev.puklic.voice.codec

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * JNA mapping of the subset of libopus we need for Puklic voice.
 *
 * Function bindings cover encoder/decoder lifecycle plus the two CTLs we set
 * (bitrate, complexity, in-band FEC, DTX). All other Opus features are out of scope.
 *
 * Reference: https://opus-codec.org/docs/opus_api-1.5/group__opus__encoder.html
 *
 * The `OpusEncoder*` / `OpusDecoder*` C handles are opaque to us — we hold them
 * as JNA [Pointer]s and pass them through.
 */
internal interface LibOpus : Library {

    // --- Encoder lifecycle ---

    fun opus_encoder_create(
        Fs: Int,
        channels: Int,
        application: Int,
        error: IntByReference,
    ): Pointer

    fun opus_encoder_destroy(st: Pointer)

    fun opus_encode(
        st: Pointer,
        pcm: ShortArray,
        frame_size: Int,
        data: ByteArray,
        max_data_bytes: Int,
    ): Int

    /**
     * `opus_encoder_ctl` is a variadic C function (`int opus_encoder_ctl(OpusEncoder*, int, ...)`).
     * JNA dispatches variadic calls correctly only when the trailing parameter is declared as
     * `Object...` (java varargs); otherwise the ABI handling differs on some platforms
     * (notably arm64 macOS) and the call fails with `OPUS_BAD_ARG (-1)`.
     */
    fun opus_encoder_ctl(st: Pointer, request: Int, vararg args: Any): Int

    // --- Decoder lifecycle ---

    fun opus_decoder_create(Fs: Int, channels: Int, error: IntByReference): Pointer

    fun opus_decoder_destroy(st: Pointer)

    fun opus_decode(
        st: Pointer,
        data: ByteArray?,
        len: Int,
        pcm: ShortArray,
        frame_size: Int,
        decode_fec: Int,
    ): Int

    // --- Error strings ---

    fun opus_strerror(error: Int): String

    companion object {
        /**
         * JNA looks for `libopus.dylib` on macOS, `libopus.so.0` / `libopus.so` on Linux,
         * `opus.dll` on Windows. On macOS Homebrew installs `/opt/homebrew/lib/libopus.dylib`
         * (arm64) or `/usr/local/lib/libopus.dylib` (x86_64) which JNA finds via the system
         * loader's standard search paths.
         *
         * On Linux the SONAME is `libopus.so.0`; we hint via the lookup name `"opus"`,
         * which JNA expands to `libopus.so` / `libopus.so.0` depending on platform.
         */
        val INSTANCE: LibOpus by lazy {
            // Augment JNA's library search path with common system locations that the JVM
            // dylib loader does NOT include by default. On Apple Silicon Homebrew lives at
            // /opt/homebrew/lib (not on the default dyld search path for non-shell-launched
            // JVMs). On Linux apt installs to /usr/lib/x86_64-linux-gnu (multiarch) which
            // is fine for ld.so but JNA's fallback list is conservative.
            for (path in EXTRA_LIB_PATHS) {
                if (File(path).isDirectory) {
                    NativeLibrary.addSearchPath("opus", path)
                }
            }
            try {
                Native.load("opus", LibOpus::class.java)
            } catch (t: UnsatisfiedLinkError) {
                throw OpusException(
                    "Failed to load system libopus. Install via 'brew install opus' " +
                        "(macOS), 'apt install libopus0' (Debian/Ubuntu), or " +
                        "'dnf install opus' (Fedora). Original error: ${t.message}",
                )
            }
        }

        private val EXTRA_LIB_PATHS: List<String> = listOf(
            "/opt/homebrew/lib",                // macOS Homebrew arm64
            "/usr/local/lib",                   // macOS Homebrew x86_64, generic Unix
            "/usr/lib/x86_64-linux-gnu",        // Debian/Ubuntu multiarch x86_64
            "/usr/lib/aarch64-linux-gnu",       // Debian/Ubuntu multiarch arm64
            "/usr/lib64",                       // Fedora/RHEL x86_64
            "/usr/lib",                         // generic Linux
        )
    }
}

// --- libopus constants we use ---

/** `OPUS_APPLICATION_VOIP` — optimised for speech, enables in-band FEC eligibility. */
internal const val OPUS_APPLICATION_VOIP: Int = 2048

/** CTL request codes (from opus_defines.h). */
internal const val OPUS_SET_BITRATE_REQUEST: Int = 4002
internal const val OPUS_SET_COMPLEXITY_REQUEST: Int = 4010
internal const val OPUS_SET_INBAND_FEC_REQUEST: Int = 4012
internal const val OPUS_SET_DTX_REQUEST: Int = 4016

/** `OPUS_OK` — success return. */
internal const val OPUS_OK: Int = 0
