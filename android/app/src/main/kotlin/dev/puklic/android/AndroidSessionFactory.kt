package dev.puklic.android

import android.content.Context
import co.touchlab.kermit.Logger
import dev.puklic.ids.UserId
import dev.puklic.platform.NotificationService
import dev.puklic.platform.android.RuntimePermissions
import dev.puklic.platform.android.voice.PermissionGatedVoiceClient
import dev.puklic.platform.android.voice.VoiceServiceController
import dev.puklic.protocol.discord.DiscordDomainEvent
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.DiscordSessionBridge
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.ktorGatewayTransportFactory
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.repositories.ChannelOrchestrator
import dev.puklic.repositories.DmListOrchestrator
import dev.puklic.repositories.GuildOrchestrator
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.repositories.NotificationDispatcher
import dev.puklic.repositories.Orchestrators
import dev.puklic.repositories.OutboundMessageWorker
import dev.puklic.repositories.PresenceOrchestrator
import dev.puklic.repositories.ReadStateOrchestrator
import dev.puklic.repositories.RoleStore
import dev.puklic.repositories.TypingOrchestrator
import dev.puklic.repositories.UserOrchestrator
import dev.puklic.repositories.VoiceStateRepository
import dev.puklic.session.DiscordSession
import dev.puklic.session.DmCreator
import dev.puklic.session.FriendInviter
import dev.puklic.session.ServerJoiner
import dev.puklic.session.adapter.GatewayEventSourceAdapter
import dev.puklic.session.adapter.MessageGatewayAdapter
import dev.puklic.session.adapter.SessionTransportImpl
import dev.puklic.voice.NoOpVoiceClient
import dev.puklic.voice.VoiceClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

private const val LOG_TAG = "AndroidSessionFactory"
private const val MILLIS_PER_SECOND = 1_000L

/** Everything a [DiscordSession] needs that outlives a single session (token). */
internal class SessionDependencies(
    val context: Context,
    val applicationScope: CoroutineScope,
    val httpClient: HttpClient,
    val stores: AndroidStores,
    val notifications: NotificationService,
    val roleStore: RoleStore,
    val permissions: RuntimePermissions,
)

private val crashGuard = CoroutineExceptionHandler { _, ex ->
    Logger.e(LOG_TAG, ex) { "Uncaught coroutine exception (suppressed to keep the app alive)" }
}

/** The application-wide scope: supervised, so one failing child never takes the app down. */
internal fun newApplicationScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

/**
 * Builds a fresh [DiscordSession] for [token]: REST client, gateway, bridges, orchestrators and the
 * voice client, all owned by a session scope that is a child of the application scope, so cancelling
 * the session tears everything down.
 */
internal fun buildAndroidSession(token: String, deps: SessionDependencies): DiscordSession {
    val sessionScope = newSessionScope(deps.applicationScope)
    val rest = DiscordRestClient(deps.httpClient, token)
    val gateway = GatewayConnection(sessionScope, token, ktorGatewayTransportFactory(deps.httpClient))
    val selfUserId = MutableStateFlow<UserId?>(null)
    val gatewayBridge = newGatewayBridge(gateway, sessionScope, selfUserId)
    val sessionBridge = DiscordSessionBridge(rest)
    val events = GatewayEventSourceAdapter(gatewayBridge, sessionScope)
    val inputs = OrchestratorInputs(sessionScope, events, DiscordMessageBridge(rest), deps, selfUserId)
    val orchestrators = buildOrchestrators(inputs)
    startNotifications(inputs, orchestrators)
    return DiscordSession(
        applicationScope = deps.applicationScope,
        token = token,
        transport = SessionTransportImpl(bridge = sessionBridge, gateway = gateway, scope = sessionScope),
        orchestrators = orchestrators,
        voiceClient = buildVoiceClient(sessionScope, deps),
        dmCreator = DmCreator { recipientId -> sessionBridge.createOrOpenDm(recipientId) },
        friendInviter = FriendInviter { username, discriminator -> sessionBridge.addFriend(username, discriminator) },
        serverJoiner = ServerJoiner { code -> sessionBridge.joinServer(code) },
    )
}

private fun newSessionScope(applicationScope: CoroutineScope): CoroutineScope =
    CoroutineScope(SupervisorJob(applicationScope.coroutineContext[Job]) + Dispatchers.Default + crashGuard)

private fun newGatewayBridge(
    gateway: GatewayConnection,
    sessionScope: CoroutineScope,
    selfUserId: MutableStateFlow<UserId?>,
): DiscordGatewayBridge {
    val bridge = DiscordGatewayBridge(
        gateway = gateway,
        scope = sessionScope,
        onUnknown = { type -> Logger.d(LOG_TAG) { "Unhandled gateway event: $type" } },
        selfUserIdProvider = { selfUserId.value },
    )
    sessionScope.launch {
        bridge.events
            .filterIsInstance<DiscordDomainEvent.Ready>()
            .collect { selfUserId.value = it.selfUser.id }
    }
    return bridge
}

private class OrchestratorInputs(
    val sessionScope: CoroutineScope,
    val events: GatewayEventSourceAdapter,
    val messageBridge: DiscordMessageBridge,
    val deps: SessionDependencies,
    val selfUserId: MutableStateFlow<UserId?>,
)

private fun buildOrchestrators(inputs: OrchestratorInputs): Orchestrators {
    val scope = inputs.sessionScope
    val events = inputs.events
    val stores = inputs.deps.stores
    val messageGateway = MessageGatewayAdapter(inputs.messageBridge, stores.channels)
    val guild = GuildOrchestrator(scope, events, stores.guilds)
    val outboundWorker = OutboundMessageWorker(
        sessionScope = scope,
        outboundQueue = stores.outboundQueue,
        messageGateway = messageGateway,
        storage = stores.messages,
    )
    return Orchestrators(
        messages = buildMessageOrchestrator(inputs, messageGateway),
        outboundWorker = outboundWorker,
        presence = PresenceOrchestrator(scope, events),
        typing = TypingOrchestrator(scope, events) { System.currentTimeMillis() / MILLIS_PER_SECOND },
        guild = guild,
        channel = buildChannelOrchestrator(inputs, guild),
        user = UserOrchestrator(scope, events, stores.users),
        dms = DmListOrchestrator(scope, events),
        voiceStates = VoiceStateRepository(scope, events),
        readState = ReadStateOrchestrator(sessionScope = scope, gatewaySource = events, storage = stores.readState),
    ).also { outboundWorker.start() }
}

private fun buildMessageOrchestrator(inputs: OrchestratorInputs, messageGateway: MessageGatewayAdapter) =
    MessageOrchestrator(
        sessionScope = inputs.sessionScope,
        gatewaySource = inputs.events,
        messageGateway = messageGateway,
        storage = inputs.deps.stores.messages,
        userStorage = inputs.deps.stores.users,
        outboundQueue = inputs.deps.stores.outboundQueue,
        selfUserIdProvider = { inputs.selfUserId.value },
    )

private fun buildChannelOrchestrator(inputs: OrchestratorInputs, guild: GuildOrchestrator) = ChannelOrchestrator(
    sessionScope = inputs.sessionScope,
    gatewaySource = inputs.events,
    storage = inputs.deps.stores.channels,
    persistenceContext = Dispatchers.IO,
    roleStore = inputs.deps.roleStore,
    guildOwnerProvider = { guildId -> guild.guilds.value.firstOrNull { it.id == guildId }?.ownerId },
)

private fun startNotifications(inputs: OrchestratorInputs, orchestrators: Orchestrators) {
    NotificationDispatcher(
        sessionScope = inputs.sessionScope,
        gatewaySource = inputs.events,
        channelRepository = inputs.deps.stores.channels,
        notificationService = inputs.deps.notifications,
        selfUserIdProvider = { inputs.selfUserId.value },
        visibilityCheck = orchestrators.channel,
    )
}

/**
 * Android has no voice media engine yet, so the engine is [NoOpVoiceClient]. It is wrapped so that the
 * microphone permission is requested on the first join and the foreground service follows the session
 * state; both apply unchanged to a real engine.
 */
private fun buildVoiceClient(sessionScope: CoroutineScope, deps: SessionDependencies): VoiceClient {
    val client = PermissionGatedVoiceClient(NoOpVoiceClient(), deps.permissions)
    VoiceServiceController(deps.context, sessionScope).bind(client)
    return client
}
