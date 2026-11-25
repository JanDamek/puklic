package dev.puklic.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformFailed
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * System clipboard through `ClipboardManager`. Text is read and written directly; an image is written
 * to the app cache and shared as a `content://` URI served by the library's `FileProvider`, which is
 * how Android clipboards carry binary data. Reading text succeeds only while the app has focus
 * (Android 10+), which holds for the in-app paste actions that call it.
 */
class AndroidPlatformClipboard(private val context: Context) : PlatformClipboard {
    private val clipboard: ClipboardManager = checkNotNull(context.getSystemService(ClipboardManager::class.java)) {
        "ClipboardManager is unavailable"
    }

    override suspend fun setText(text: String) = clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))

    override suspend fun getText(): String? = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

    override suspend fun setImage(bytes: ByteArray, mimeType: String) {
        val file = File(clipboardDirectory(), "${UUID.randomUUID()}.${mimeType.substringAfter('/', "bin")}")
        try {
            file.writeBytes(bytes)
        } catch (e: IOException) {
            throw PlatformFailed("Cannot stage clipboard image: ${e.message}")
        }
        val uri = FileProvider.getUriForFile(context, context.fileProviderAuthority(), file)
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, CLIP_LABEL, uri))
    }

    private fun clipboardDirectory(): File =
        File(context.cacheDir, CLIPBOARD_DIR).apply {
            deleteRecursively()
            mkdirs()
        }

    private companion object {
        const val CLIP_LABEL = "Puklic"
        const val CLIPBOARD_DIR = "clipboard"
    }
}
