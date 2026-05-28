package dev.puklic.ios

import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.persistence.sqldelight.AttachmentCacheIndexImpl
import dev.puklic.persistence.sqldelight.ChannelRepositoryImpl
import dev.puklic.persistence.sqldelight.GuildRepositoryImpl
import dev.puklic.persistence.sqldelight.IosDriverFactory
import dev.puklic.persistence.sqldelight.LocalDraftRepositoryImpl
import dev.puklic.persistence.sqldelight.MessageRepositoryImpl
import dev.puklic.persistence.sqldelight.OutboundQueueImpl
import dev.puklic.persistence.sqldelight.ReadStateRepositoryImpl
import dev.puklic.persistence.sqldelight.UserPreferencesRepositoryImpl
import dev.puklic.persistence.sqldelight.UserRepositoryImpl
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.ios.IosNotificationService
import dev.puklic.platform.ios.IosPlatformOpen
import dev.puklic.platform.ios.IosPlatformPaths
import dev.puklic.platform.ios.IosSecureStorage
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
import dev.puklic.ui.resolvers.CdnEmojiResolver
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.RepositoryMentionResolver
import dev.puklic.voice.NoOpVoiceClient
import dev.puklic.voice.VoiceClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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
import platform.UIKit.UIViewController

private const val LOG_TAG = "IosDependencyGraph"

/**
 * Manual DI graph for the iOS app — analogue of `desktop/app/.../DependencyGraph.kt` adapted
 * for the iOS App Store build (Apache-2.0 only, no voice/screenshare per the GPL guard).
 *
 * Wires:
 *  - [IosPlatformPaths] / [IosSecureStorage] / [IosPlatformOpen] / [IosNotificationService]
 *  - [IosDriverFactory] → SQLDelight `NativeSqliteDriver`
 *  - [HttpClient] with the **Darwin** engine + websockets + JSON content negotiation
 *  - Per-session [DiscordSession] factory with [NoOpVoiceClient] (voice excluded on iOS)
 *  - [SessionManager] for auto-restore + token storage
 *
 * Construct once at app launch (in Swift via `IosDependencyGraph.create()`), then call
 * [puklicAppRootViewController] to obtain the Compose-hosting view controller.
 */
@Suppress("LongParameterList")
public class IosDependencyGraph private constructor(
    public val applicationScope: CoroutineScope,
    public val platformPaths: PlatformPaths,
    public val platformOpen: PlatformOpen,
    public val secureStorage: SecureStorage,
    public val sessionManager: SessionManager,
    public val httpClient: HttpClient,
    public val database: PuklicDatabase,
    public val mentionResolver: MentionResolver,
    public val emojiResolver: EmojiResolver,
    public val userPreferences: UserPreferencesRepository,
) {
    /**
     * Swift-callable entry — builds the `UIViewController` that hosts the Puklic Compose UI.
     * The caller (Slice 4 `iosApp.xcodeproj`) embeds this as the root view controller.
     */
    public fun puklicAppRootViewController(): UIViewController =
        puklicAppRootViewController(
            sessionManager = sessionManager,
            preferences = userPreferences,
            mentionResolver = mentionResolver,
            emojiResolver = emojiResolver,
            platformOpen = platformOpen,
        )

    public companion object {
        public fun create(): IosDependencyGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val paths: PlatformPaths = IosPlatformPaths()
            val opener: PlatformOpen = IosPlatformOpen()
            val storage: SecureStorage = IosSecureStorage()
            val notifications: NotificationService = IosNotificationService()

            val driver: SqlDriver = IosDriverFactory().createDriver()
            val database = PuklicDatabase(driver)

            // Native coroutines do not expose a separate IO dispatcher (Dispatchers.IO is JVM-only).
            // Default uses the worker pool which is the correct equivalent on iOS.
            val ioDispatcher = Dispatchers.Default
            val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
            val messageStore = MessageRepositoryImpl(database, ioDispatcher, nowMs)
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
            val userPreferences = UserPreferencesRepositoryImpl(database, ioDispatcher)

            val httpClient = HttpClient(Darwin) {
                install(ContentNegotiation) { json(discordJson()) }
                install(WebSockets) {}
                expectSuccess = false
            }

            val roleStore = RoleStore()

            val sessionFactory: (String) -> DiscordSession = { token ->
                buildIosSession(
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

            applicationScope.launch {
                runCatching { sessionManager.loadStoredSession() }
                    .onFailure { ex -> Logger.w(LOG_TAG, ex) { "Auto-restore failed" } }
            }

            return IosDependencyGraph(
                applicationScope = applicationScope,
                platformPaths = paths,
                platformOpen = opener,
                secureStorage = storage,
                sessionManager = sessionManager,
                httpClient = httpClient,
                database = database,
                mentionResolver = RepositoryMentionResolver(userStore, channelStore, roleStore),
                emojiResolver = CdnEmojiResolver,
                userPreferences = userPreferences,
            )
        }

        @Suppress("LongParameterList", "LongMethod")
        private fun buildIosSession(
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
                persistenceContext = Dispatchers.Default,
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

            // Voice excluded on iOS by design (App Store build is GPL-free and the sandbox
            // forbids raw UDP without entitlements that are not granted for general apps).
            // See docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md §3.2.
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
