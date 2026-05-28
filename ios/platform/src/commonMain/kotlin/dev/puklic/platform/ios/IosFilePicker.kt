package dev.puklic.platform.ios

import dev.puklic.platform.FilePicker
import dev.puklic.platform.PickedFile
import dev.puklic.platform.PlatformUnavailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS attachment picker via [UIDocumentPickerViewController].
 *
 * Presented modally on the foreground key window's root view controller.
 * The delegate is strongly retained for the lifetime of the picker via a
 * private registry so UIKit's weak delegate reference does not cause GC.
 *
 * If no foreground window / root view controller exists (e.g. the app is
 * still launching or running in a process without a `UIApplication` host),
 * [pick] throws [PlatformUnavailable] — this is a runtime precondition, not
 * a stub: callers should only invoke `pick` from UI code that already has
 * a window.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFilePicker : FilePicker {

    override suspend fun pick(allowMultiple: Boolean): List<PickedFile> = withContext(Dispatchers.Main) {
        val host = resolveRootViewController()
            ?: throw PlatformUnavailable("No foreground UIWindowScene / root view controller — cannot present UIDocumentPickerViewController")
        val deferred = CompletableDeferred<List<PickedFile>>()
        val delegate = PickerDelegate(deferred)
        activeDelegates.add(delegate)
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
        picker.allowsMultipleSelection = allowMultiple
        picker.delegate = delegate
        host.presentViewController(picker, animated = true, completion = null)
        try {
            deferred.await()
        } finally {
            activeDelegates.remove(delegate)
        }
    }

    private fun resolveRootViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        for (sceneAny in app.connectedScenes) {
            val scene = sceneAny as? UIWindowScene ?: continue
            for (windowAny in scene.windows) {
                val window = windowAny as? UIWindow ?: continue
                window.rootViewController?.let { return it }
            }
        }
        return null
    }

    companion object {
        // Strong retention for in-flight delegates; UIKit holds the delegate weakly.
        private val activeDelegates = mutableListOf<PickerDelegate>()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PickerDelegate(
    private val deferred: CompletableDeferred<List<PickedFile>>,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        val picked = urls.mapNotNull { url ->
            val started = url.startAccessingSecurityScopedResource()
            try {
                val data = NSData.dataWithContentsOfURL(url) ?: return@mapNotNull null
                val bytes = data.toByteArray()
                val name = url.lastPathComponent ?: "attachment"
                val ext = url.pathExtension
                val mime = if (!ext.isNullOrEmpty()) {
                    UTType.typeWithFilenameExtension(ext)?.preferredMIMEType
                } else null
                PickedFile(filename = name, bytes = bytes, contentType = mime)
            } finally {
                if (started) url.stopAccessingSecurityScopedResource()
            }
        }
        deferred.complete(picked)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        deferred.complete(emptyList())
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}
