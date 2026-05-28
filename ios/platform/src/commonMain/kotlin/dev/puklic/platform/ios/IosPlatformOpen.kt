package dev.puklic.platform.ios

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformOpen
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS [PlatformOpen] backed by `UIApplication.sharedApplication.openURL`.
 *
 * `openFile` and `openInFolder` build `NSURL.fileURLWithPath:` URLs — iOS will
 * route file URLs to the Files app (if the URL is in a shared container).
 * For in-app sandbox files there is no equivalent of macOS Finder reveal, so
 * the file URL is opened in whatever handler the system finds.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlatformOpen : PlatformOpen {

    override suspend fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: throw PlatformFailed("Invalid URL: $url")
        openOnMain(nsUrl)
    }

    override suspend fun openFile(path: Path) {
        val nsUrl = NSURL.fileURLWithPath(path)
        openOnMain(nsUrl)
    }

    override suspend fun openInFolder(path: Path) {
        // iOS has no "reveal in folder" — opening the parent directory URL is the
        // closest conceptual equivalent on platforms that honour it (Files app).
        val parent = path.substringBeforeLast('/', missingDelimiterValue = path)
        val nsUrl = NSURL.fileURLWithPath(parent.ifEmpty { path })
        openOnMain(nsUrl)
    }

    private suspend fun openOnMain(nsUrl: NSURL) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            UIApplication.sharedApplication.openURL(
                url = nsUrl,
                options = emptyMap<Any?, Any>(),
                completionHandler = { success ->
                    if (success) cont.resume(Unit)
                    else cont.resumeWithException(PlatformFailed("UIApplication.openURL refused: $nsUrl"))
                },
            )
        }
    }

}
