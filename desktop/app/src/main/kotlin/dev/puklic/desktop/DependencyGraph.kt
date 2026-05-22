package dev.puklic.desktop

import co.touchlab.kermit.Logger
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.sqldelight.AttachmentCacheIndexImpl
import dev.puklic.persistence.sqldelight.ChannelRepositoryImpl
import dev.puklic.persistence.sqldelight.GuildRepositoryImpl
import dev.puklic.persistence.sqldelight.JvmDriverFactory
import dev.puklic.persistence.sqldelight.LocalDraftRepositoryImpl
import dev.puklic.persistence.sqldelight.MessageRepositoryImpl
import dev.puklic.persistence.sqldelight.OutboundQueueImpl
import dev.puklic.persistence.sqldelight.ReadStateRepositoryImpl
import dev.puklic.persistence.sqldelight.UserRepositoryImpl
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.linux.LinuxPlatformPaths
import dev.puklic.platform.linux.LinuxSecureStorage
import dev.puklic.platform.macos.MacOsPlatformPaths
import dev.puklic.platform.macos.MacOsSecureStorage
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.DiscordSessionBridge
import dev.puklic.protocol.discord.discordJson
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.repositories.ChannelOrchestrator
import dev.puklic.repositories.GuildOrchestrator
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.repositories.Orchestrators
import dev.puklic.repositories.OutboundMessageWorker
import dev.puklic.repositories.PresenceOrchestrator
import dev.puklic.repositories.TypingOrchestrator
import dev.puklic.repositories.UserOrchestrator
import dev.puklic.session.DiscordSession
import dev.puklic.session.SessionManager
import dev.puklic.session.adapter.GatewayEventSourceAdapter
import dev.puklic.session.adapter.MessageGatewayAdapter
import dev.puklic.session.adapter.SessionTransportImpl
import dev.puklic.ui.navigation.RootComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import java.nio.file.Paths

private const val LOG_TAG = "DependencyGraph"

/**
 * Manual DI graph for the desktop app. Phase 1 step 15-16 wires:
 *  - OS-appropriate `PlatformPaths` + `SecureStorage`
 *  - SQLite-backed persistence repositories
 *  - Ktor HttpClient + Discord protocol bridges (REST + gateway)
 *  - Per-session [DiscordSession] factory that constructs orchestrators, the outbound worker
 *    and the real [SessionTransportImpl]
 *  - [SessionManager] + [RootComponent] for the UI
 *
 * The session factory is per-token: each call creates a fresh `sessionScope` (child of
 * [applicationScope]) plus a fresh REST client, gateway connection, bridges, adapters and
 * orchestrators. Cancelling the session scope tears everything down via supervisor cascade.
 */
@Suppress("LongParameterList")
public class DependencyGraph private constructor(
    public val applicationScope: CoroutineScope,
    public val platformPaths: PlatformPaths,
    public val secureStorage: SecureStorage,
    public val sessionManager: SessionManager,
    public val rootComponent: RootComponent,
    public val httpClient: HttpClient,
    public val database: PuklicDatabase,
) {
    public companion object {
        public fun create(): DependencyGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val isMac = detectMac()
            val paths: PlatformPaths = if (isMac) MacOsPlatformPaths() else LinuxPlatformPaths()
            val storage: SecureStorage = if (isMac) MacOsSecureStorage() else LinuxSecureStorage()

            val driverFactory = JvmDriverFactory(
                JvmDriverFactory.DbPath.File(Paths.get(paths.databaseFile())),
            )
            val database = PuklicDatabase(driverFactory.createDriver())

            val ioDispatcher = Dispatchers.IO
            val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
            val messageStore = MessageRepositoryImpl(database, ioDispatcher)
            val guildStore = GuildRepositoryImpl(database, ioDispatcher, nowMs)
            val channelStore = ChannelRepositoryImpl(database, ioDispatcher, nowMs)
            val userStore = UserRepositoryImpl(database, ioDispatcher, nowMs)
            val outboundQueue = OutboundQueueImpl(database, ioDispatcher)
            @Suppress("UNUSED_VARIABLE")
            val localDrafts = LocalDraftRepositoryImpl(database, ioDispatcher)
            @Suppress("UNUSED_VARIABLE")
            val readState = ReadStateRepositoryImpl(database, ioDispatcher)
            @Suppress("UNUSED_VARIABLE")
            val attachmentCache = AttachmentCacheIndexImpl(database, ioDispatcher)

            val httpClient = HttpClient(CIO) {
                install(ContentNegotiation) { json(discordJson()) }
                install(WebSockets) {}
                expectSuccess = false
            }

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
                )
            }

            val sessionManager = SessionManager(
                applicationScope = applicationScope,
                secureStorage = storage,
                sessionFactory = sessionFactory,
            )

            val lifecycle = LifecycleRegistry()
            val ctx = DefaultComponentContext(lifecycle = lifecycle)
            lifecycle.resume()
            val root = RootComponent(ctx, sessionManager)

            return DependencyGraph(
                applicationScope = applicationScope,
                platformPaths = paths,
                secureStorage = storage,
                sessionManager = sessionManager,
                rootComponent = root,
                httpClient = httpClient,
                database = database,
            )
        }

        @Suppress("LongParameterList")
        private fun buildSession(
            token: String,
            applicationScope: CoroutineScope,
            httpClient: HttpClient,
            messageStore: MessageRepositoryImpl,
            guildStore: GuildRepositoryImpl,
            channelStore: ChannelRepositoryImpl,
            userStore: UserRepositoryImpl,
            outboundQueue: OutboundQueueImpl,
        ): DiscordSession {
            val sessionJob = SupervisorJob(applicationScope.coroutineContext[Job])
            val sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)

            val rest = DiscordRestClient(httpClient, token)
            val gatewayTransportFactory = ktorGatewayTransportFactory(httpClient)
            val gateway = GatewayConnection(sessionScope, token, gatewayTransportFactory)

            val gatewayBridge = DiscordGatewayBridge(
                gateway = gateway,
                scope = sessionScope,
                onUnknown = { type -> Logger.d(LOG_TAG) { "Unhandled gateway event: $type" } },
            )
            val messageBridge = DiscordMessageBridge(rest)
            val sessionBridge = DiscordSessionBridge(rest)

            val gatewayEventSource = GatewayEventSourceAdapter(gatewayBridge, sessionScope)
            val messageGateway = MessageGatewayAdapter(messageBridge)

            val messageOrchestrator = MessageOrchestrator(
                sessionScope = sessionScope,
                gatewaySource = gatewayEventSource,
                messageGateway = messageGateway,
                storage = messageStore,
                outboundQueue = outboundQueue,
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
            val channelOrch = ChannelOrchestrator(sessionScope, gatewayEventSource, channelStore)
            val userOrch = UserOrchestrator(sessionScope, gatewayEventSource, userStore)
            val orchestrators = Orchestrators(
                messages = messageOrchestrator,
                outboundWorker = outboundWorker,
                presence = presence,
                typing = typing,
                guild = guildOrch,
                channel = channelOrch,
                user = userOrch,
            )
            outboundWorker.start()

            val transport = SessionTransportImpl(
                bridge = sessionBridge,
                gateway = gateway,
                scope = sessionScope,
            )
            return DiscordSession(
                applicationScope = applicationScope,
                token = token,
                transport = transport,
                orchestrators = orchestrators,
            )
        }

        private fun detectMac(): Boolean {
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            return "mac" in osName || "darwin" in osName
        }
    }
}
