package dev.puklic.desktop.platform

import dev.puklic.platform.FilePicker
import dev.puklic.platform.PickedFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AWT-backed file picker. Works under Linux/Wayland (via XWayland) and macOS dev builds. The
 * dialog is blocking, so the call is dispatched onto [Dispatchers.IO] and the result is read off
 * the EDT. Returned files have their bytes loaded eagerly — the caller is responsible for
 * enforcing the size limit before initiating an upload.
 */
public class AwtFilePicker(private val parent: Frame? = null) : FilePicker {

    override suspend fun pick(allowMultiple: Boolean): List<PickedFile> = withContext(Dispatchers.IO) {
        val dialog = FileDialog(parent, "Attach files", FileDialog.LOAD).apply {
            isMultipleMode = allowMultiple
            isVisible = true
        }
        val selected: Array<File> = dialog.files ?: emptyArray()
        selected.map { it.toPickedFile() }
    }

    private fun File.toPickedFile(): PickedFile = PickedFile(
        filename = name,
        bytes = readBytes(),
        contentType = guessContentType(this),
    )

    private fun guessContentType(file: File): String? =
        runCatching { URLConnection.guessContentTypeFromName(file.name) }.getOrNull()
}
