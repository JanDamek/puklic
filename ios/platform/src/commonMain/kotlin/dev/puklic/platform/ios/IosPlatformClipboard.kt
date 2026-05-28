package dev.puklic.platform.ios

import dev.puklic.platform.PlatformClipboard
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIPasteboard
import platform.UniformTypeIdentifiers.UTType

/**
 * iOS clipboard via `UIPasteboard.generalPasteboard`. Images are written with
 * the UTI derived from the MIME type via `UTType(MIMEType:)`; if the MIME is
 * not recognised, the public `public.data` UTI is used so the data is at
 * least pasteable into apps that accept binary blobs.
 *
 * `UIPasteboard` must be touched on the main thread.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPlatformClipboard : PlatformClipboard {

    override suspend fun setText(text: String) = withContext(Dispatchers.Main) {
        UIPasteboard.generalPasteboard.string = text
    }

    override suspend fun getText(): String? = withContext(Dispatchers.Main) {
        UIPasteboard.generalPasteboard.string
    }

    override suspend fun setImage(bytes: ByteArray, mimeType: String) = withContext(Dispatchers.Main) {
        val data = bytes.toNSData()
        val uti = UTType.typeWithMIMEType(mimeType)?.identifier ?: "public.data"
        UIPasteboard.generalPasteboard.setData(data, forPasteboardType = uti)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
