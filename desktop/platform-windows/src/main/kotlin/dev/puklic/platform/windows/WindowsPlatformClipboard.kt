package dev.puklic.platform.windows

import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformUnavailable
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text clipboard through the AWT system clipboard. Image clipboard is not supported yet. */
class WindowsPlatformClipboard : PlatformClipboard {

    override suspend fun setText(text: String) = withContext(Dispatchers.IO) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }

    override suspend fun getText(): String? = withContext(Dispatchers.IO) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
    }

    override suspend fun setImage(bytes: ByteArray, mimeType: String): Unit =
        throw PlatformUnavailable("Image clipboard not supported on Windows")
}
