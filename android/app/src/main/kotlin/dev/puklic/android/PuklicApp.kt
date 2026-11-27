package dev.puklic.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dev.puklic.platform.android.connection.BackgroundConnectionHost
import dev.puklic.platform.android.connection.BackgroundConnectionStatus
import dev.puklic.platform.android.voice.VoiceDisconnectHandler
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toOkioPath
import java.io.File

private const val IMAGE_CACHE_DIR_NAME = "image-cache"
private const val IMAGE_CACHE_MAX_BYTES = 50L * 1024 * 1024
private const val IMAGE_MEMORY_CACHE_PERCENT = 0.10

/**
 * Application entry point. Owns the process-wide [AndroidDependencyGraph], the bounded Coil image
 * loader, the voice "Disconnect" notification action and the background connection service host.
 */
class PuklicApp : Application(), SingletonImageLoader.Factory, VoiceDisconnectHandler, BackgroundConnectionHost {
    lateinit var graph: AndroidDependencyGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AndroidDependencyGraph.create(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .components { add(KtorNetworkFetcherFactory(graph.httpClient)) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(graph.platformPaths.cacheDir, IMAGE_CACHE_DIR_NAME).toOkioPath())
                    .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .build()

    override fun disconnectVoice() = graph.disconnectVoice()

    override val connectionStatus: StateFlow<BackgroundConnectionStatus>
        get() = graph.backgroundConnection.connectionStatus

    override val voiceActive: StateFlow<Boolean>
        get() = graph.backgroundConnection.voiceActive

    override suspend fun isBackgroundConnectionEnabled(): Boolean = graph.backgroundConnection.isBackgroundConnectionEnabled()

    override fun disableBackgroundConnection() = graph.backgroundConnection.disableBackgroundConnection()
}
