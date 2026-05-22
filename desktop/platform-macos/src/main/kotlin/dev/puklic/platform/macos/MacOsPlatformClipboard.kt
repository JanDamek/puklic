package dev.puklic.platform.macos

import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Text via `pbcopy` / `pbpaste`. Images via AWT Toolkit clipboard (platform-agnostic JVM API).
 */
class MacOsPlatformClipboard(
    private val runner: CommandRunner = CommandRunner(),
) : PlatformClipboard {

    override suspend fun setText(text: String) {
        if (!runner.isOnPath(PBCOPY)) throw PlatformUnavailable("$PBCOPY not found")
        runner.run(args = listOf(PBCOPY), stdin = text).successOrThrow(PBCOPY)
    }

    override suspend fun getText(): String? {
        if (!runner.isOnPath(PBPASTE)) throw PlatformUnavailable("$PBPASTE not found")
        val result = runner.run(args = listOf(PBPASTE))
        return if (result.succeeded) result.stdout else null
    }

    override suspend fun setImage(bytes: ByteArray, mimeType: String) = withContext(Dispatchers.IO) {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw PlatformFailed("Unsupported image bytes (mime=$mimeType)")
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) image
                else throw UnsupportedOperationException("Unsupported flavor: $flavor")
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    companion object {
        internal const val PBCOPY = "/usr/bin/pbcopy"
        internal const val PBPASTE = "/usr/bin/pbpaste"
    }
}
