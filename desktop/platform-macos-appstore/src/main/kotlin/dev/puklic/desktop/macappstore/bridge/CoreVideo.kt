package dev.puklic.desktop.macappstore.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for CoreVideo.framework — `CVPixelBuffer` is the input image
 * type VideoToolbox compression sessions consume and decompression sessions
 * produce.
 */
internal interface CoreVideo : Library {

    fun CVPixelBufferCreate(
        allocator: Pointer?,
        width: Long,
        height: Long,
        pixelFormatType: Int,
        pixelBufferAttributes: Pointer?,
        pixelBufferOut: PointerByReference,
    ): Int

    fun CVPixelBufferLockBaseAddress(buffer: Pointer?, lockFlags: Long): Int
    fun CVPixelBufferUnlockBaseAddress(buffer: Pointer?, lockFlags: Long): Int

    fun CVPixelBufferGetBaseAddressOfPlane(buffer: Pointer?, planeIndex: Long): Pointer?
    fun CVPixelBufferGetBytesPerRowOfPlane(buffer: Pointer?, planeIndex: Long): Long

    fun CVPixelBufferGetWidth(buffer: Pointer?): Long
    fun CVPixelBufferGetHeight(buffer: Pointer?): Long
    fun CVPixelBufferGetBytesPerRow(buffer: Pointer?): Long
    fun CVPixelBufferGetBaseAddress(buffer: Pointer?): Pointer?

    companion object {
        val INSTANCE: CoreVideo by lazy { Native.load("CoreVideo", CoreVideo::class.java) }

        /** `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange` four-char-code '420v' = 0x34323076. */
        const val PIXEL_FORMAT_420V_NV12: Int = 0x3432_3076

        /** `kCVPixelFormatType_32BGRA` four-char-code 'BGRA' = 0x42475241. */
        const val PIXEL_FORMAT_32BGRA: Int = 0x4247_5241
    }
}
