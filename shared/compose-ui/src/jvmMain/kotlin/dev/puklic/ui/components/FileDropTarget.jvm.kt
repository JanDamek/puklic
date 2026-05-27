@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.puklic.ui.components

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import dev.puklic.platform.PickedFile
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URLConnection

/**
 * JVM/Compose Desktop actual for [fileDropTarget]. Uses the awtTransferable from the drop event
 * to pull the file list, then loads each file's bytes synchronously. The size limit is enforced
 * downstream in [dev.puklic.ui.screens.main.ComposerViewModel.send].
 */
public actual fun Modifier.fileDropTarget(onFilesDropped: (List<PickedFile>) -> Unit): Modifier =
    this.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> dragEventHasFiles(event) },
        target = object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = extractFiles(event)
                if (files.isEmpty()) return false
                onFilesDropped(files.map { it.toPickedFile() })
                return true
            }
        },
    )

private fun dragEventHasFiles(event: DragAndDropEvent): Boolean =
    runCatching {
        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    }.getOrDefault(false)

@Suppress("UNCHECKED_CAST")
private fun extractFiles(event: DragAndDropEvent): List<File> =
    runCatching {
        val transferable = event.awtTransferable
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
    }.getOrDefault(emptyList())

private fun File.toPickedFile(): PickedFile = PickedFile(
    filename = name,
    bytes = readBytes(),
    contentType = runCatching { URLConnection.guessContentTypeFromName(name) }.getOrNull(),
)
