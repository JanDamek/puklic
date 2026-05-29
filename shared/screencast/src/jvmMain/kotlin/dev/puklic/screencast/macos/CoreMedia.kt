package dev.puklic.screencast.macos

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * CoreMedia + CoreVideo + AudioToolbox C function bindings used by the
 * ScreenCaptureKit bridge to extract BGRA video frames and stereo Float32
 * audio samples from `CMSampleBuffer`s delivered to the stream output
 * delegate.
 *
 * Only the functions we actually call are declared — no auto-generated
 * binding bloat. All Apple system frameworks are loaded by their tbd name
 * via `Native.load`; the JVM resolves them through the macOS dyld cache.
 *
 * Documentation references:
 *   - CoreVideo: https://developer.apple.com/documentation/corevideo
 *   - CoreMedia: https://developer.apple.com/documentation/coremedia
 *   - AudioToolbox AudioBufferList: https://developer.apple.com/documentation/coreaudiotypes/audiobufferlist
 */
internal object CoreMedia {

    val coreVideo: CoreVideoLibrary by lazy {
        Native.load("CoreVideo", CoreVideoLibrary::class.java)
    }

    val coreMedia: CoreMediaLibrary by lazy {
        Native.load("CoreMedia", CoreMediaLibrary::class.java)
    }

    /** `kCVPixelBufferLock_ReadOnly = 0x00000001`. */
    const val LOCK_READ_ONLY: Long = 1L

    @Suppress("FunctionNaming")
    interface CoreVideoLibrary : Library {
        fun CVPixelBufferLockBaseAddress(buf: Pointer, flags: Long): Int
        fun CVPixelBufferUnlockBaseAddress(buf: Pointer, flags: Long): Int
        fun CVPixelBufferGetBaseAddress(buf: Pointer): Pointer
        fun CVPixelBufferGetBytesPerRow(buf: Pointer): NativeLong
        fun CVPixelBufferGetWidth(buf: Pointer): NativeLong
        fun CVPixelBufferGetHeight(buf: Pointer): NativeLong
        fun CVPixelBufferGetPixelFormatType(buf: Pointer): Int
    }

    @Suppress("FunctionNaming")
    interface CoreMediaLibrary : Library {
        fun CMSampleBufferGetImageBuffer(sample: Pointer): Pointer
        fun CMSampleBufferGetDataBuffer(sample: Pointer): Pointer
        fun CMSampleBufferGetNumSamples(sample: Pointer): NativeLong

        /**
         * `OSStatus CMBlockBufferCopyDataBytes(CMBlockBufferRef src, size_t offset, size_t length, void* destination);`
         */
        fun CMBlockBufferCopyDataBytes(buf: Pointer, offset: NativeLong, length: NativeLong, dst: Pointer): Int

        fun CMBlockBufferGetDataLength(buf: Pointer): NativeLong

        /**
         * `OSStatus CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
         *      CMSampleBufferRef, size_t* bufferListSizeOut, AudioBufferList* bufferListOut,
         *      size_t bufferListSize, CFAllocatorRef bbStructAllocator, CFAllocatorRef bbMemoryAllocator,
         *      uint32_t flags, CMBlockBufferRef* blockBufferOut)`
         *
         * We pass `NULL` allocators (default malloc) and `flags = 0`.
         */
        fun CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sample: Pointer,
            bufferListSizeOut: LongByReference?,
            bufferListOut: Pointer?,
            bufferListSize: NativeLong,
            bbStructAllocator: Pointer?,
            bbMemoryAllocator: Pointer?,
            flags: Int,
            blockBufferOut: PointerByReference?,
        ): Int
    }
}
