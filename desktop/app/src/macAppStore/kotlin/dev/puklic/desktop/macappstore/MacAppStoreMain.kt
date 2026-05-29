package dev.puklic.desktop.macappstore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.sqldelight.ChannelRepositoryImpl
import dev.puklic.persistence.sqldelight.GuildRepositoryImpl
import dev.puklic.persistence.sqldelight.JvmDriverFactory
import dev.puklic.persistence.sqldelight.MessageRepositoryImpl
import dev.puklic.persistence.sqldelight.OutboundQueueImpl
import dev.puklic.persistence.sqldelight.UserPreferencesRepositoryImpl
import dev.puklic.persistence.sqldelight.UserRepositoryImpl
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.macos.MacOsNotificationService
import dev.puklic.platform.macos.MacOsPlatformOpen
import dev.puklic.platform.macos.MacOsPlatformPaths
import dev.puklic.platform.macos.MacOsSecureStorage
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.DiscordSessionBridge
import dev.puklic.protocol.discord.discordJson
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.ktorGatewayTransportFactory
import dev.puklic.protocol.discord.rest.DiscordLoginClient
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.repositories.ChannelOrchestrator
import dev.puklic.repositories.DmListOrchestrator
import dev.puklic.repositories.GuildOrchestrator
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.repositories.NotificationDispatcher
import dev.puklic.repositories.Orchestrators
import dev.puklic.repositories.OutboundMessageWorker
import dev.puklic.repositories.PresenceOrchestrator
import dev.puklic.repositories.RoleStore
import dev.puklic.repositories.TypingOrchestrator
import dev.puklic.repositories.UserOrchestrator
import dev.puklic.repositories.VoiceStateRepository
import dev.puklic.session.DiscordSession
import dev.puklic.session.DmCreator
import dev.puklic.session.SessionManager
import dev.puklic.session.adapter.DiscordCredentialsLoginAdapter
import dev.puklic.session.adapter.GatewayEventSourceAdapter
import dev.puklic.session.adapter.MessageGatewayAdapter
import dev.puklic.session.adapter.SessionTransportImpl
import dev.puklic.ui.PuklicApp
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.resolvers.CdnEmojiResolver
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.RepositoryMentionResolver
import dev.puklic.voice.NoOpVoiceClient
import dev.puklic.voice.VoiceClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import okio.Path.Companion.toOkioPath
import java.awt.Dimension
import java.awt.Taskbar
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Entry point for the Mac App Store variant of Puklic Desktop.
 *
 * This file is the single Kotlin source in the `macAppStore` source set
 * of `:desktop:app`. Its sole purpose: launch the Compose Desktop window
 * with a dependency graph that NEVER touches `:shared:voice` (GPL-3.0 +
 * FFmpeg-GPL + libdave) — the Mac App Store ship is Apache-2.0 / MIT /
 * BSD only, enforced by `verifyMacAppStoreNoGplDeps` against the resolved
 * `macAppStoreRuntimeClasspath`.
 *
 * Posture decision (FP-14a §0, FP-14d): voice is wired as [NoOpVoiceClient]
 * — same shape as the iOS App Store ship (`:ios:app`'s
 * `IosDependencyGraph`). A real Apple-native voice client composed from
 * the FP-14c codec/transport primitives (`JnaLibopusEncoder`,
 * `JnaVideoToolboxH264Encoder`, `JnaNwConnectionUdpTransport`) lives in
 * `:desktop:platform-macos-appstore` and is brought online by a follow-up
 * conceptual slice — NOT a temporary in this slice, but a parallel
 * deliverable tracked separately (mirror of iOS where voice is not yet
 * wired in the App Store ship).
 *
 * See:
 *   - docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md §4
 *   - docs/03_infrastructure/architect-reports/2026-05-29-fp14d-gradle-packaging.md §3
 *   - ios/app/src/commonMain/kotlin/dev/puklic/ios/IosDependencyGraph.kt (analogue)
 */

private const val LOG_TAG = "MacAppStoreMain"
private const val WINDOW_ICON_RESOURCE: String = "icons/puklic-256.png"
private const val DOCK_ICON_RESOURCE: String = "icons/puklic-512.png"
private const val IMAGE_CACHE_DIR_NAME: String = "image-cache"
private const val IMAGE_CACHE_MAX_BYTES: Long = 50L * 1024 * 1024

/** Mac App Store entry point. Mirrors `dev.puklic.desktop.MainKt` but constructs
 *  [MacAppStoreDependencyGraph] (no `:shared:voice` runtime dep). */
public fun main(): Unit = application {
    configureDockIcon()
    val graph = remember { MacAppStoreDependencyGraph.create() }
    SingletonImageLoader.setSafe { context -> buildImageLoader(context, graph) }
    val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Puklic",
        icon = painterResource(WINDOW_ICON_RESOURCE),
    ) {
        window.minimumSize = Dimension(480, 600)
        Column(modifier = Modifier.fillMaxSize()) {
            PuklicApp(
                root = graph.rootComponent,
                mentionResolver = graph.mentionResolver,
                emojiResolver = graph.emojiResolver,
                platformOpen = graph.platformOpen,
            )
        }
    }
}

private fun buildImageLoader(
    context: PlatformContext,
    graph: MacAppStoreDependencyGraph,
): ImageLoader {
    val cacheDir = File(graph.platformPaths.cacheDir, IMAGE_CACHE_DIR_NAME)
    return ImageLoader.Builder(context)
        .crossfade(true)
        .components { add(KtorNetworkFetcherFactory(graph.httpClient)) }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.toOkioPath())
                .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                .build()
        }
        .build()
}

private fun configureDockIcon() {
    if (!Taskbar.isTaskbarSupported()) return
    runCatching {
        val classLoader = ::configureDockIcon.javaClass.classLoader
        val stream = classLoader.getResourceAsStream(DOCK_ICON_RESOURCE) ?: return
        val image = stream.use(ImageIO::read) ?: return
        Taskbar.getTaskbar().iconImage = image
    }
}

/**
 * Manual DI graph for the Mac App Store variant. Direct analogue of
 * `dev.puklic.ios.IosDependencyGraph` adapted to JVM + macOS.
 *
 * Wires:
 *   - [MacOsPlatformPaths] / [MacOsSecureStorage] / [MacOsPlatformOpen] /
 *     [MacOsNotificationService] from `:desktop:platform-macos`.
 *   - [JvmDriverFactory] → SQLDelight desktop SQLite driver.
 *   - [HttpClient] with the **CIO** engine + WebSockets + JSON content negotiation.
 *   - Per-session [DiscordSession] with [NoOpVoiceClient] (voice not wired in v1
 *     Mac App Store ship — same posture as iOS App Store ship).
 *
 * Does NOT reference `dev.puklic.voice.DefaultVoiceClient`,
 * `dev.puklic.voice.MainGatewayBridgeAdapter`, or any other symbol from
 * `:shared:voice` — that module is excluded from `macAppStoreRuntimeClasspath`.
 */
@Suppress("LongParameterList")
public class MacAppStoreDependencyGraph private constructor(
    public val applicationScope: CoroutineScope,
    public val platformPaths: PlatformPaths,
    public val platformOpen: PlatformOpen,
    public val secureStorage: SecureStorage,
    public val sessionManager: SessionManager,
    public val rootComponent: RootComponent,
    public val httpClient: HttpClient,
    public val mentionResolver: MentionResolver,
    public val emojiResolver: EmojiResolver,
) {
    public companion object {
        public fun create(): MacAppStoreDependencyGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val paths: PlatformPaths = MacOsPlatformPaths()
            val opener: PlatformOpen = MacOsPlatformOpen()
            val storage: SecureStorage = MacOsSecureStorage()
            val notifications: NotificationService = MacOsNotificationService()

            val driverFactory = JvmDriverFactory(
                JvmDriverFactory.DbPath.File(Paths.get(paths.databaseFile())),
            )
            val database = PuklicDatabase(driverFactory.createDriver())

            val ioDispatcher = Dispatchers.IO
            val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
            val messageStore = MessageRepositoryImpl(database, ioDispatcher, nowMs)
            val guildStore = GuildRepositoryImpl(database, ioDispatcher, nowMs)
            val channelStore = ChannelRepositoryImpl(database, ioDispatcher, nowMs)
            val userStore = UserRepositoryImpl(database, ioDispatcher, nowMs)
            val outboundQueue = OutboundQueueImpl(database, ioDispatcher)
            val userPreferences = UserPreferencesRepositoryImpl(database, ioDispatcher)

            val httpClient = HttpClient(CIO) {
                install(ContentNegotiation) { json(discordJson()) }
                install(WebSockets) {}
                expectSuccess = false
            }

            val roleStore = RoleStore()

            val sessionFactory: (String) -> DiscordSession = { token ->
                buildSession(
                    token = token,
                    applicationScope = applicationScope,
                    httpClient = httpClient,
                    messageStore = messageStore,
                    guildStore = guildStore,
                    channelStore = channelStore,
                    userStore = userStore,
                    outboundQueue = outboundQueue,
                    notificationService = notifications,
                    roleStore = roleStore,
                )
            }

            val credentialsLogin = DiscordCredentialsLoginAdapter(DiscordLoginClient(httpClient))

            val sessionManager = SessionManager(
                applicationScope = applicationScope,
                secureStorage = storage,
                sessionFactory = sessionFactory,
                credentialsLogin = credentialsLogin,
            )

            val lifecycle = LifecycleRegistry()
            val ctx = DefaultComponentContext(lifecycle = lifecycle)
            lifecycle.resume()
            val root = RootComponent(ctx, sessionManager, preferences = userPreferences)

            applicationScope.launch {
                runCatching { sessionManager.loadStoredSession() }
                    .onFailure { ex -> Logger.w(LOG_TAG, ex) { "Auto-restore failed" } }
            }

            return MacAppStoreDependencyGraph(
                applicationScope = applicationScope,
                platformPaths = paths,
                platformOpen = opener,
                secureStorage = storage,
                sessionManager = sessionManager,
                rootComponent = root,
                httpClient = httpClient,
                mentionResolver = RepositoryMentionResolver(userStore, channelStore, roleStore),
                emojiResolver = CdnEmojiResolver,
            )
        }

        @Suppress("LongParameterList", "LongMethod")
        private fun buildSession(
            token: String,
            applicationScope: CoroutineScope,
            httpClient: HttpClient,
            messageStore: MessageRepositoryImpl,
            guildStore: GuildRepositoryImpl,
            channelStore: ChannelRepositoryImpl,
            userStore: UserRepositoryImpl,
            outboundQueue: OutboundQueueImpl,
            notificationService: NotificationService,
            roleStore: RoleStore,
        ): DiscordSession {
            val sessionJob = SupervisorJob(applicationScope.coroutineContext[Job])
            val sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)

            val rest = DiscordRestClient(httpClient, token)
            val gatewayTransportFactory = ktorGatewayTransportFactory(httpClient)
            val gateway = GatewayConnection(sessionScope, token, gatewayTransportFactory)

            val selfUserId = MutableStateFlow<dev.puklic.ids.UserId?>(null)

            val gatewayBridge = DiscordGatewayBridge(
                gateway = gateway,
                scope = sessionScope,
                onUnknown = { type -> Logger.d(LOG_TAG) { "Unhandled gateway event: $type" } },
                selfUserIdProvider = { selfUserId.value },
            )
            val messageBridge = DiscordMessageBridge(rest)
            val sessionBridge = DiscordSessionBridge(rest)

            val gatewayEventSource = GatewayEventSourceAdapter(gatewayBridge, sessionScope)
            val messageGateway = MessageGatewayAdapter(messageBridge, channelStore)

            sessionScope.launch {
                gatewayBridge.events
                    .filterIsInstance<dev.puklic.protocol.discord.DiscordDomainEvent.Ready>()
                    .collect { selfUserId.value = it.selfUser.id }
            }

            val messageOrchestrator = MessageOrchestrator(
                sessionScope = sessionScope,
                gatewaySource = gatewayEventSource,
                messageGateway = messageGateway,
                storage = messageStore,
                userStorage = userStore,
                outboundQueue = outboundQueue,
                selfUserIdProvider = { selfUserId.value },
            )
            val outboundWorker = OutboundMessageWorker(
                sessionScope = sessionScope,
                outboundQueue = outboundQueue,
                messageGateway = messageGateway,
                storage = messageStore,
            )
            val presence = PresenceOrchestrator(sessionScope, gatewayEventSource)
            val typing = TypingOrchestrator(
                sessionScope = sessionScope,
                gatewaySource = gatewayEventSource,
                nowEpochSeconds = { Clock.System.now().epochSeconds },
            )
            val guildOrch = GuildOrchestrator(sessionScope, gatewayEventSource, guildStore)
            val channelOrch = ChannelOrchestrator(
                sessionScope = sessionScope,
                gatewaySource = gatewayEventSource,
                storage = channelStore,
                persistenceContext = Dispatchers.IO,
                roleStore = roleStore,
                guildOwnerProvider = { guildId ->
                    guildOrch.guilds.value.firstOrNull { it.id == guildId }?.ownerId
                },
            )
            val userOrch = UserOrchestrator(sessionScope, gatewayEventSource, userStore)
            val dmListOrch = DmListOrchestrator(sessionScope, gatewayEventSource)
            val voiceStateRepo = VoiceStateRepository(sessionScope, gatewayEventSource)
            val orchestrators = Orchestrators(
                messages = messageOrchestrator,
                outboundWorker = outboundWorker,
                presence = presence,
                typing = typing,
                guild = guildOrch,
                channel = channelOrch,
                user = userOrch,
                dms = dmListOrch,
                voiceStates = voiceStateRepo,
            )
            outboundWorker.start()

            @Suppress("UNUSED_VARIABLE")
            val notificationDispatcher = NotificationDispatcher(
                sessionScope = sessionScope,
                gatewaySource = gatewayEventSource,
                channelRepository = channelStore,
                notificationService = notificationService,
                selfUserIdProvider = { selfUserId.value },
                visibilityCheck = channelOrch,
            )

            val transport = SessionTransportImpl(
                bridge = sessionBridge,
                gateway = gateway,
                scope = sessionScope,
            )

            // Voice not wired in v1 Mac App Store ship — same posture as iOS
            // App Store ship (`IosDependencyGraph` line 301).
            val voiceClient: VoiceClient = NoOpVoiceClient()

            val dmCreator = DmCreator { recipientId ->
                sessionBridge.createOrOpenDm(recipientId)
            }

            return DiscordSession(
                applicationScope = applicationScope,
                token = token,
                transport = transport,
                orchestrators = orchestrators,
                voiceClient = voiceClient,
                dmCreator = dmCreator,
            )
        }
    }
}
