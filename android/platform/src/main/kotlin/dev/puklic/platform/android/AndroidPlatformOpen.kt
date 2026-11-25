package dev.puklic.platform.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.puklic.platform.Path
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformUnavailable
import java.io.File

/**
 * Hands URLs and files to the default Android handler through `ACTION_VIEW`. Files are exposed with
 * a temporary read grant on a `content://` URI from the library's `FileProvider`, which serves the
 * app's `filesDir` and `cacheDir`. Android has no file-manager "reveal in folder" action, so
 * [openInFolder] reports [PlatformUnavailable] and callers use [openFile] instead.
 */
class AndroidPlatformOpen(private val context: Context) : PlatformOpen {

    override suspend fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() !in OPENABLE_SCHEMES) throw PlatformFailed("Unsupported URL scheme: ${uri.scheme}")
        start(Intent(Intent.ACTION_VIEW, uri))
    }

    override suspend fun openFile(path: Path) {
        val file = File(path)
        if (!file.isFile) throw PlatformFailed("File does not exist: $path")
        val uri = fileUri(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(intent)
    }

    override suspend fun openInFolder(path: Path): Unit =
        throw PlatformUnavailable("Android has no file manager action to reveal a folder; use openFile")

    private fun fileUri(file: File): Uri =
        try {
            FileProvider.getUriForFile(context, context.fileProviderAuthority(), file)
        } catch (e: IllegalArgumentException) {
            throw PlatformFailed("File is outside the shareable app directories: ${e.message}")
        }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: FALLBACK_MIME_TYPE

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw PlatformFailed("No application can handle ${intent.data}: ${e.message}")
        }
    }

    private companion object {
        val OPENABLE_SCHEMES = setOf("http", "https", "mailto")
        const val FALLBACK_MIME_TYPE = "application/octet-stream"
    }
}
