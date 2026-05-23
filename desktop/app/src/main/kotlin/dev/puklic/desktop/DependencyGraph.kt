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
import dev.puklic.persistence.sqldelight.UserPreferencesRepositoryImpl
import dev.puklic.persistence.sqldelight.UserRepositoryImpl
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.linux.LinuxPlatformOpen
import dev.puklic.platform.linux.LinuxPlatformPaths
import dev.puklic.platform.linux.LinuxSecureStorage
import dev.puklic.platform.macos.MacOsPlatformOpen
import dev.puklic.platform.macos.MacOsPlatformPaths
import dev.puklic.platform.macos.MacOsSecureStorage
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.DiscordSessionBridge
import dev.puklic.protocol.discord.discordJson
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.rest.DiscordLoginClient
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
import dev.puklic.session.adapter.DiscordCredentialsLoginAdapter
import dev.puklic.session.adapter.GatewayEventSourceAdapter
import dev.puklic.session.adapter.MessageGatewayAdapter
import dev.puklic.session.adapter.SessionTransportImpl
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.resolvers.CdnEmojiResolver
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.RepositoryMentionResolver
import dev.puklic.desktop.update.UpdateChecker
import dev.puklic.desktop.update.UpdateCheckerScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import dev.puklic.domain.GuildTextChannel
import dev.puklic.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    public val platformOpen: PlatformOpen,
    public val secureStorage: SecureStorage,
    public val sessionManager: SessionManager,
    public val rootComponent: RootComponent,
    public val httpClient: HttpClient,
    public val database: PuklicDatabase,
    public val mentionResolver: MentionResolver,
    public val emojiResolver: EmojiResolver,
    public val updateScheduler: UpdateCheckerScheduler,
) {
    public companion object {
        public fun create(): DependencyGraph {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val isMac = detectMac()
            val paths: PlatformPaths = if (isMac) MacOsPlatformPaths() else LinuxPlatformPaths()
            val opener: PlatformOpen = if (isMac) MacOsPlatformOpen() else LinuxPlatformOpen()
            val storage: SecureStorage = if (isMac) MacOsSecureStorage() else LinuxSecureStorage()

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
            @Suppress("UNUSED_VARIABLE")
            val localDrafts = LocalDraftRepositoryImpl(database, ioDispatcher)
            @Suppress("UNUSED_VARIABLE")
            val readState = ReadStateRepositoryImpl(database, ioDispatcher)
            @Suppress("UNUSED_VARIABLE")
            val attachmentCache = AttachmentCacheIndexImpl(database, ioDispatcher)
            val userPreferences = UserPreferencesRepositoryImpl(database, ioDispatcher)

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

            // Kick off auto-restore in the background. RootComponent observes
            // sessionManager.bootstrap + activeSession and renders BootstrappingScreen
            // while this is in flight, then routes to Main (valid token) or Login
            // (no token / 401). Token validation errors are swallowed inside
            // SessionManager.loadStoredSession — never log the token value.
            applicationScope.launch {
                runCatching { sessionManager.loadStoredSession() }
                    .onFailure { ex -> Logger.w(LOG_TAG, ex) { "Auto-restore failed" } }
            }

            if (System.getProperty("puklic.dev.autotest") == "true") {
                applicationScope.launch { runAutoTest(sessionManager) }
            }

            // Opt-in update check via GitHub Releases API. Default ON; user can disable later
            // via `puklic.update.autoCheck` system property (set false) without restart logic
            // needing to change — the scheduler re-reads `enabled()` on every tick.
            val updateChecker = UpdateChecker(httpClient)
            val updateScheduler = UpdateCheckerScheduler(
                checker = updateChecker,
                scope = applicationScope,
                enabled = { System.getProperty("puklic.update.autoCheck", "true") != "false" },
            )
            updateScheduler.start()

            return DependencyGraph(
                applicationScope = applicationScope,
                platformPaths = paths,
                platformOpen = opener,
                secureStorage = storage,
                sessionManager = sessionManager,
                rootComponent = root,
                httpClient = httpClient,
                database = database,
                mentionResolver = RepositoryMentionResolver(userStore, channelStore),
                emojiResolver = CdnEmojiResolver,
                updateScheduler = updateScheduler,
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
            val messageGateway = MessageGatewayAdapter(messageBridge, channelStore)

            // Self-user id tracker — populated when Ready arrives, consumed by
            // MessageOrchestrator to mark reactions originating from the current user.
            val selfUserId = kotlinx.coroutines.flow.MutableStateFlow<dev.puklic.ids.UserId?>(null)
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
                persistenceContext = kotlinx.coroutines.Dispatchers.IO,
            )
            val userOrch = UserOrchestrator(sessionScope, gatewayEventSource, userStore)
            val dmListOrch = dev.puklic.repositories.DmListOrchestrator(sessionScope, gatewayEventSource)
            val voiceStateRepo = dev.puklic.repositories.VoiceStateRepository(sessionScope, gatewayEventSource)
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

            val transport = SessionTransportImpl(
                bridge = sessionBridge,
                gateway = gateway,
                scope = sessionScope,
            )

            // Voice wiring — behind VoiceFeatureFlag.ENABLED (slice 9/10 of
            // docs/03_infrastructure/architect-reports/2026-05-23-voice.md §13).
            val voiceClient: dev.puklic.voice.VoiceClient =
                if (dev.puklic.session.VoiceFeatureFlag.ENABLED) {
                    val mainGatewayBridge = MainGatewayBridgeAdapter(
                        gateway = gateway,
                        bridge = gatewayBridge,
                        scope = sessionScope,
                    )
                    dev.puklic.voice.DefaultVoiceClient(
                        applicationScope = sessionScope,
                        mainGateway = mainGatewayBridge,
                        selfUserIdProvider = { selfUserId.value },
                        voiceTransportFactory = dev.puklic.voice.gateway
                            .ktorVoiceGatewayTransportFactory(httpClient),
                    )
                } else {
                    dev.puklic.voice.NoOpVoiceClient()
                }

            return DiscordSession(
                applicationScope = applicationScope,
                token = token,
                transport = transport,
                orchestrators = orchestrators,
                voiceClient = voiceClient,
            )
        }

        @Suppress("MagicNumber", "NestedBlockDepth", "LongMethod")
        private suspend fun runAutoTest(sessionManager: SessionManager) {
            Logger.i(LOG_TAG) { "auto-test: waiting for active session" }
            val session = sessionManager.activeSession.filterNotNull().first()
            Logger.i(LOG_TAG) { "auto-test: session present, awaiting Connected" }
            session.state.filterIsInstance<SessionState.Connected>().first()
            val orch = session.orchestrators ?: run {
                Logger.w(LOG_TAG) { "auto-test: orchestrators null, abort" }
                return
            }
            Logger.i(LOG_TAG) { "auto-test: connected, awaiting guilds" }
            val guilds = orch.guild.guilds.filter { it.isNotEmpty() }.first()
            Logger.i(LOG_TAG) { "auto-test: iterating ${guilds.size} guild(s)" }
            for (guild in guilds) {
                runCatching {
                    Logger.i(LOG_TAG) {
                        "auto-test: testing guild id=${guild.id.value} name=${guild.name}"
                    }
                    session.transport.lazyRequestGuild(guild.id, emptyList())
                    delay(200L)
                    val channels = runCatching {
                        orch.channel.channelsForGuild(guild.id).filter { it.isNotEmpty() }.first()
                    }.getOrElse {
                        Logger.w(LOG_TAG) { "auto-test: no channels for guild ${guild.name}: ${it.message}" }
                        emptyList()
                    }
                    val textChannels = channels
                        .filterIsInstance<GuildTextChannel>()
                        .sortedBy { it.position }
                        .take(5)
                    Logger.i(LOG_TAG) {
                        "auto-test: guild=${guild.name} testing ${textChannels.size} text channels"
                    }
                    for (channel in textChannels) {
                        runCatching {
                            val result = orch.messages.loadInitial(channel.id, pageSize = 50)
                            val count = result.getOrNull() ?: -1
                            val err = result.exceptionOrNull()?.message?.take(80)
                            Logger.i(LOG_TAG) {
                                "auto-test: guild=${guild.name} channel=${channel.name ?: channel.id.value} loaded=$count err=$err"
                            }
                        }.onFailure {
                            Logger.w(LOG_TAG) {
                                "auto-test: loadInitial threw for ${channel.id.value}: ${it::class.simpleName}: ${it.message?.take(80)}"
                            }
                        }
                    }
                }.onFailure {
                    Logger.w(LOG_TAG) {
                        "auto-test: guild iteration failed for ${guild.name}: ${it::class.simpleName}: ${it.message?.take(80)}"
                    }
                }
            }
            // DMs
            val dms = runCatching {
                orch.dms.dms.filter { it.isNotEmpty() }.first()
            }.getOrElse {
                Logger.w(LOG_TAG) { "auto-test: no DMs available: ${it.message}" }
                emptyList()
            }
            Logger.i(LOG_TAG) { "auto-test: iterating ${dms.size} DM(s) (first 3)" }
            for (dm in dms.take(3)) {
                runCatching {
                    val result = orch.messages.loadInitial(dm.id, pageSize = 50)
                    val count = result.getOrNull() ?: -1
                    val err = result.exceptionOrNull()?.message?.take(80)
                    val label = dm.recipients.firstOrNull()?.username ?: dm.id.value
                    Logger.i(LOG_TAG) {
                        "auto-test: DM channel=$label loaded=$count err=$err"
                    }
                }.onFailure {
                    Logger.w(LOG_TAG) {
                        "auto-test: DM loadInitial threw for ${dm.id.value}: ${it::class.simpleName}: ${it.message?.take(80)}"
                    }
                }
            }
            Logger.i(LOG_TAG) { "auto-test: COMPLETE all guilds tested" }
        }

        private fun detectMac(): Boolean {
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            return "mac" in osName || "darwin" in osName
        }
    }
}
