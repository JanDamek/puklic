package dev.puklic.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import java.io.File
import dev.puklic.desktop.crash.CrashReporter
import dev.puklic.desktop.logging.LoggingBootstrap
import dev.puklic.desktop.update.UpdateBanner
import dev.puklic.ui.PuklicApp
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Taskbar
import javax.imageio.ImageIO

private const val WINDOW_ICON_RESOURCE: String = "icons/puklic-256.png"
private const val DOCK_ICON_RESOURCE: String = "icons/puklic-512.png"

/**
 * Subdirectory under the OS cache dir (e.g. `~/.cache/puklic/` on Linux,
 * `~/Library/Caches/Puklic/` on macOS) that holds Coil's on-disk image cache.
 * Avatars, attachment thumbnails, and Discord custom-emoji PNG/GIF responses live here.
 */
private const val IMAGE_CACHE_DIR_NAME: String = "image-cache"

/**
 * Hard cap on the disk image cache. Per CLAUDE.md "cache is always bounded" rule.
 * 50 MiB comfortably holds tens of thousands of custom emoji (~5–15 KiB each) plus
 * a few hundred avatars; Coil evicts least-recently-used entries once exceeded.
 */
private const val IMAGE_CACHE_MAX_BYTES: Long = 50L * 1024 * 1024

/**
 * Desktop entry point. Opens a 1280×800 Compose Desktop window titled "Puklic" and renders
 * [PuklicApp]. The window respects a minimum size that keeps the Compact layout usable
 * (480×600 per `docs/04_ui/adaptive-layouts.md`).
 */
public fun main(): Unit = application {
    LoggingBootstrap.install()
    CrashReporter.install()
    configureDockIcon()
    val graph = remember { DependencyGraph.create() }
    SingletonImageLoader.setSafe { context ->
        buildImageLoader(context, graph)
    }
    val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Puklic",
        icon = painterResource(WINDOW_ICON_RESOURCE),
    ) {
        window.minimumSize = Dimension(480, 600)
        val update by graph.updateScheduler.update.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            UpdateBanner(
                update = update,
                onOpenRelease = { url ->
                    graph.applicationScope.launch { graph.platformOpen.openUrl(url) }
                },
                onDismiss = { graph.updateScheduler.dismiss() },
            )
            PuklicApp(
                root = graph.rootComponent,
                mentionResolver = graph.mentionResolver,
                emojiResolver = graph.emojiResolver,
                platformOpen = graph.platformOpen,
            )
        }
    }
}

/**
 * Builds the singleton Coil [ImageLoader] used by every `AsyncImage` in the app.
 *
 * Wiring:
 *  - **Ktor fetcher** — reuses the shared [DependencyGraph.httpClient] so connection
 *    pools, proxies, and (future) Discord User-Agent live in one place.
 *  - **Bounded disk cache** — pinned at [IMAGE_CACHE_MAX_BYTES] under the OS cache
 *    dir resolved by `PlatformPaths`. Same cache backs avatars, attachment
 *    thumbnails, and emoji — Coil keys by URL so emoji never evict avatars
 *    disproportionately.
 *  - **Crossfade** — soft fade-in on first load; cache hits paint instantly.
 */
private fun buildImageLoader(context: PlatformContext, graph: DependencyGraph): ImageLoader {
    val cacheDir = File(graph.platformPaths.cacheDir, IMAGE_CACHE_DIR_NAME)
    return ImageLoader.Builder(context)
        .crossfade(true)
        .components {
            add(KtorNetworkFetcherFactory(graph.httpClient))
            // Note: Coil 3.1 does not yet ship a JVM-desktop GIF decoder (the
            // `coil-gif` artifact is Android-only). Discord animated custom
            // emoji (`<a:name:id>` → `.gif`) therefore display the first frame
            // only; animation will be enabled when Coil publishes a multiplatform
            // animated decoder. The URL still resolves and cache still hits, so
            // there is no broken path — only a missing playback feature.
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.toOkioPath())
                .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                .build()
        }
        .build()
}

/**
 * Sets the platform taskbar/Dock icon when supported. Silently no-ops on platforms that do
 * not expose taskbar icon control (e.g. some Linux session managers).
 */
private fun configureDockIcon() {
    if (!Taskbar.isTaskbarSupported()) return
    runCatching {
        val classLoader = ::configureDockIcon.javaClass.classLoader
        val stream = classLoader.getResourceAsStream(DOCK_ICON_RESOURCE) ?: return
        val image = stream.use(ImageIO::read) ?: return
        Taskbar.getTaskbar().iconImage = image
    }
}
