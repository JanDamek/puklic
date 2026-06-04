package dev.puklic.ui.screens.main

import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.VoiceMember
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.LastPosition
import dev.puklic.persistence.repository.LastPositionCodec
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.domain.UserSummary
import dev.puklic.domain.GuildChannel
import dev.puklic.repositories.NewDmSearch
import dev.puklic.repositories.Orchestrators
import dev.puklic.repositories.PresenceState
import dev.puklic.repositories.ReadStateView
import dev.puklic.session.DmCreator
import dev.puklic.session.FriendInviter
import dev.puklic.session.ServerJoiner
import dev.puklic.session.SessionManager
import dev.puklic.session.SessionTransport
import dev.puklic.ui.screens.settings.SettingsCategory
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.IncomingCall
import dev.puklic.voice.VoiceBusyException
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import dev.puklic.voice.screenshare.ScreenShareState
import dev.puklic.voice.screenshare.ScreenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Snapshot of the main screen's guild + channel state. Backed by the live orchestrators
 * (when available); falls back to empty placeholder when no session is active (e.g. tests).
 */
/**
 * Top-level navigation scope. The user is either viewing a guild (selectedGuildId set) or the
 * DM "Home" landing (dmHomeSelected = true). Mutually exclusive; default is [Empty].
 */
public sealed interface NavigationScope {
    public data object Empty : NavigationScope
    public data class GuildSelected(val id: GuildId) : NavigationScope
    public data object DmHome : NavigationScope
}

public data class MainScreenState(
    val guilds: List<Guild> = emptyList(),
    val channelsForSelectedGuild: List<Channel> = emptyList(),
    val dmChannels: List<DmChannel> = emptyList(),
    val scope: NavigationScope = NavigationScope.Empty,
    val selectedChannelId: ChannelId? = null,
) {
    val selectedGuildId: GuildId? get() = (scope as? NavigationScope.GuildSelected)?.id
    val isDmHome: Boolean get() = scope is NavigationScope.DmHome
}

/**
 * Decompose-style ViewModel for the three-pane authenticated UI. When [orchestrators] is non-null,
 * [state] reflects the live guild + channel reactive Flows; selection is local to this view-model.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public class MainViewModel(
    componentContext: ComponentContext,
    public val orchestrators: Orchestrators? = null,
    public val sessionTransport: SessionTransport? = null,
    externalScope: CoroutineScope? = null,
    private val preferences: UserPreferencesRepository? = null,
    private val secureStorage: dev.puklic.platform.SecureStorage? = null,
    initialPosition: LastPosition = LastPosition.Empty,
    public val voiceClient: Any? = null,
    private val sessionManager: SessionManager? = null,
    private val dmCreator: DmCreator? = null,
    private val friendInviter: FriendInviter? = null,
    private val serverJoiner: ServerJoiner? = null,
) : ComponentContext by componentContext {

    // ----- New DM picker state (issue #17) -----

    /** Domain-level snapshot of the New-DM picker. */
    public data class NewDmState(
        val isOpen: Boolean = false,
        val query: String = "",
        val results: List<UserSummary> = emptyList(),
        val isSubmitting: Boolean = false,
    )

    private val newDmState = MutableStateFlow(NewDmState())
    public val newDm: StateFlow<NewDmState> = newDmState.asStateFlow()

    private val newDmSearch: NewDmSearch? = orchestrators?.let { orch ->
        NewDmSearch(
            users = orch.user,
            dms = orch.dms,
            selfUserId = { orch.user.selfUser.value?.id },
        )
    }

    /** Open the picker — resets query/results to a clean slate. */
    public fun openNewDm() {
        newDmState.value = NewDmState(isOpen = true)
    }

    public fun closeNewDm() {
        newDmState.value = NewDmState()
    }

    /** Update the search query and refresh the result list (cancels in-flight searches). */
    public fun updateNewDmQuery(query: String) {
        val search = newDmSearch
        newDmState.update { it.copy(query = query) }
        if (search == null) return
        scope.launch {
            val matches = runCatching { search.search(query) }.getOrElse {
                Logger.w("MainViewModel", it) { "newDm search failed" }
                emptyList()
            }
            newDmState.update { state ->
                if (state.query == query) state.copy(results = matches) else state
            }
        }
    }

    /**
     * Issue `POST /users/@me/channels` for [recipientId], then select the returned DM channel.
     * Idempotent on Discord's side — picking the same user repeatedly opens the same channel.
     * Errors are logged and surfaced via the existing snackbar mechanism.
     */
    public fun pickNewDmRecipient(recipientId: UserId) {
        val creator = dmCreator ?: run {
            Logger.w("MainViewModel") { "pickNewDmRecipient called with no DmCreator wired" }
            return
        }
        newDmState.update { it.copy(isSubmitting = true) }
        scope.launch {
            val outcome = runCatching { creator.createOrOpenDm(recipientId) }
                .getOrElse { Result.failure(it) }
            outcome
                .onSuccess { channel ->
                    selectDmHome()
                    selectChannel(channel.id)
                    closeNewDm()
                }
                .onFailure { t ->
                    Logger.w("MainViewModel", t) { "createDm failed for recipient=$recipientId" }
                    newDmState.update { it.copy(isSubmitting = false) }
                    _snackbarMessage.emit(SNACKBAR_NEW_DM_FAILED)
                }
        }
    }


    // ----- Add-friend + join-server dialogs (issue #80) -----

    /** Domain-level snapshot of the Add-Friend dialog. */
    public data class AddFriendState(
        val isOpen: Boolean = false,
        val query: String = "",
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val successMessage: String? = null,
    )

    /** Domain-level snapshot of the Join-Server dialog. */
    public data class JoinServerState(
        val isOpen: Boolean = false,
        val query: String = "",
        val isSubmitting: Boolean = false,
        val errorMessage: String? = null,
        val successMessage: String? = null,
    )

    private val _addFriend = MutableStateFlow(AddFriendState())
    public val addFriend: StateFlow<AddFriendState> = _addFriend.asStateFlow()

    private val _joinServer = MutableStateFlow(JoinServerState())
    public val joinServer: StateFlow<JoinServerState> = _joinServer.asStateFlow()

    public fun openAddFriend() { _addFriend.value = AddFriendState(isOpen = true) }
    public fun closeAddFriend() { _addFriend.value = AddFriendState() }
    public fun updateAddFriendQuery(q: String) {
        _addFriend.update { it.copy(query = q, errorMessage = null, successMessage = null) }
    }

    public fun openJoinServer() { _joinServer.value = JoinServerState(isOpen = true) }
    public fun closeJoinServer() { _joinServer.value = JoinServerState() }
    public fun updateJoinServerQuery(q: String) {
        _joinServer.update { it.copy(query = q, errorMessage = null, successMessage = null) }
    }

    /**
     * Submit the friend request. Accepts a pomelo handle ("name"), legacy "name#1234", or raw
     * input that is forwarded verbatim as a username (Discord's relationships endpoint accepts
     * those for users with discoverability enabled).
     */
    public fun submitAddFriend() {
        val inviter = friendInviter ?: run {
            _addFriend.update { it.copy(errorMessage = ADD_FRIEND_UNAVAILABLE) }
            return
        }
        val raw = _addFriend.value.query.trim()
        if (raw.isEmpty()) {
            _addFriend.update { it.copy(errorMessage = ADD_FRIEND_EMPTY) }
            return
        }
        val (username, discriminator) = parseAddFriendQuery(raw)
        _addFriend.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
        scope.launch {
            val result = runCatching { inviter.addFriend(username, discriminator) }
                .getOrElse { Result.failure(it) }
            result
                .onSuccess {
                    _addFriend.update {
                        it.copy(isSubmitting = false, successMessage = ADD_FRIEND_SUCCESS, query = "")
                    }
                }
                .onFailure { t ->
                    Logger.w("MainViewModel", t) { "addFriend failed for query=$raw" }
                    _addFriend.update { it.copy(isSubmitting = false, errorMessage = ADD_FRIEND_FAILED) }
                }
        }
    }

    public fun submitJoinServer() {
        val joiner = serverJoiner ?: run {
            _joinServer.update { it.copy(errorMessage = JOIN_SERVER_UNAVAILABLE) }
            return
        }
        val code = parseInviteCode(_joinServer.value.query)
        if (code.isEmpty()) {
            _joinServer.update { it.copy(errorMessage = JOIN_SERVER_EMPTY) }
            return
        }
        _joinServer.update { it.copy(isSubmitting = true, errorMessage = null, successMessage = null) }
        scope.launch {
            val result = runCatching { joiner.joinServer(code) }
                .getOrElse { Result.failure(it) }
            result
                .onSuccess {
                    _joinServer.update {
                        it.copy(isSubmitting = false, successMessage = JOIN_SERVER_SUCCESS, query = "")
                    }
                }
                .onFailure { t ->
                    Logger.w("MainViewModel", t) { "joinServer failed for code=$code" }
                    _joinServer.update { it.copy(isSubmitting = false, errorMessage = JOIN_SERVER_FAILED) }
                }
        }
    }

    /**
     * Sign the user out of the current Discord session. Disconnects the gateway, clears the
     * active session and wipes the persisted token from [SecureStorage] so the next app start
     * lands on [LoginScreen]. The router observes [SessionManager.activeSession] and
     * automatically transitions away from [RouterState.Main] once the session is cleared.
     */
    public suspend fun logout() {
        sessionManager?.endSession(wipeToken = true)
    }

    // ----- Settings overlay state (architect report v2 §5) -----

    private val _settingsOpen = MutableStateFlow(false)
    public val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _selectedSettingsCategory = MutableStateFlow(SettingsCategory.ACCOUNT)
    public val selectedSettingsCategory: StateFlow<SettingsCategory> =
        _selectedSettingsCategory.asStateFlow()

    /**
     * Open the Settings overlay. When [category] is non-null, navigates to that category.
     * When null, preserves whatever category was last selected (Discord behavior).
     */
    public fun openSettings(category: SettingsCategory? = null) {
        category?.let { _selectedSettingsCategory.value = it }
        _settingsOpen.value = true
    }

    public fun closeSettings() {
        _settingsOpen.value = false
    }

    public fun selectSettingsCategory(category: SettingsCategory) {
        _selectedSettingsCategory.value = category
    }

    // ----- UserInfoRow state surfaces (architect report v2 §3) -----

    /** Self user emitted by [UserOrchestrator]. Null pre-READY or when no session is wired. */
    public val selfUser: StateFlow<UserSummary?> =
        orchestrators?.user?.selfUser
            ?: MutableStateFlow<UserSummary?>(null).asStateFlow()

    /** Live presence map. Empty when no session is wired. */
    public val presences: StateFlow<Map<UserId, PresenceState>> =
        orchestrators?.presence?.presences
            ?: MutableStateFlow(emptyMap<UserId, PresenceState>()).asStateFlow()

    /** Mirror of [VoiceClient.state]; [VoiceState.Idle] when no voice client is wired. */
    public val voiceState: StateFlow<VoiceState> =
        (voiceClient as? VoiceClient)?.state
            ?: MutableStateFlow<VoiceState>(VoiceState.Idle).asStateFlow()

    /** Mirror of [VoiceClient.daveState]; [DaveUiState.Off] when no voice client is wired. */
    public val daveState: StateFlow<DaveUiState> =
        (voiceClient as? VoiceClient)?.daveState
            ?: MutableStateFlow<DaveUiState>(DaveUiState.Off).asStateFlow()

    /**
     * Toggle self-mute on the voice client. No-op when not currently connected — the
     * UserInfoRow disables the icon in that case (architect report v2 §3 / §8).
     */
    public fun toggleSelfMute() {
        val client = voiceClient as? VoiceClient ?: return
        val current = client.state.value as? VoiceState.Connected ?: return
        client.setSelfMute(!current.selfMute)
    }

    public fun toggleSelfDeaf() {
        val client = voiceClient as? VoiceClient ?: return
        val current = client.state.value as? VoiceState.Connected ?: return
        client.setSelfDeaf(!current.selfDeaf)
    }

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val navScope = MutableStateFlow<NavigationScope>(
        when (initialPosition) {
            is LastPosition.Empty -> NavigationScope.Empty
            is LastPosition.DmHome -> NavigationScope.DmHome
            is LastPosition.Guild -> NavigationScope.GuildSelected(initialPosition.guildId)
        },
    )
    private val selectedGuild = MutableStateFlow<GuildId?>(
        (initialPosition as? LastPosition.Guild)?.guildId,
    )
    private val selectedChannel = MutableStateFlow<ChannelId?>(
        when (initialPosition) {
            is LastPosition.DmHome -> initialPosition.channelId
            is LastPosition.Guild -> initialPosition.channelId
            else -> null
        },
    )

    public val state: StateFlow<MainScreenState> = if (orchestrators == null) {
        MutableStateFlow(MainScreenState()).asStateFlow()
    } else {
        val guilds = orchestrators.guild.guilds
            .onEach { Logger.i("MainViewModel") { "viewmodel: guilds state changed, size=${it.size}" } }
        val channelFlow = selectedGuild.flatMapLatest { gid ->
            if (gid == null) flowOf(emptyList()) else orchestrators.channel.channelsForGuild(gid)
        }
        val dmFlow = orchestrators.dms.dms
        combine(guilds, channelFlow, dmFlow, navScope, selectedChannel) { gs, chs, dms, sc, cid ->
            MainScreenState(
                guilds = gs,
                channelsForSelectedGuild = chs,
                dmChannels = dms,
                scope = sc,
                selectedChannelId = cid,
            )
        }.stateIn(scope, SharingStarted.Eagerly, MainScreenState())
    }

    init {
        // If restoring into a guild, replay the lazy-subscribe so the gateway gets the
        // subscription it needs for messages + member lists. Mirrors selectGuild's launch.
        val initialGuild = (initialPosition as? LastPosition.Guild)?.guildId
        if (initialGuild != null) {
            lazySubscribeGuildBootstrap(initialGuild)
            val initialChannel = initialPosition.channelId
            if (initialChannel != null) lazySubscribeChannel(initialGuild, initialChannel)
        }
    }

    /** Switch to the DM "Home" view — clears guild selection. */
    public fun selectDmHome() {
        navScope.value = NavigationScope.DmHome
        selectedGuild.value = null
        selectedChannel.value = null
        persistPosition()
    }

    public fun selectGuild(id: GuildId) {
        navScope.value = NavigationScope.GuildSelected(id)
        selectedGuild.value = id
        selectedChannel.value = null
        persistPosition()
        lazySubscribeGuildBootstrap(id)
    }

    /**
     * Initiate a voice connection for the given guild + voice channel. Does NOT update
     * selectedChannelId — voice channels are not text-active. Fire-and-forget; the
     * resulting [VoiceClient.state] transition drives the VoiceDock UI.
     */
    public fun joinVoiceChannel(guildId: GuildId, channelId: ChannelId) {
        val client = voiceClient as? dev.puklic.voice.VoiceClient ?: return
        scope.launch {
            runCatching { client.connect(guildId, channelId) }
                .onFailure { t ->
                    Logger.w("MainViewModel", t) { "voice connect failed gid=$guildId cid=$channelId" }
                    if (t is VoiceBusyException) {
                        _snackbarMessage.emit(SNACKBAR_LEAVE_CURRENT_CALL)
                    }
                }
        }
    }

    /**
     * Transient UI messages (snackbar). Replay=0 so late collectors don't see stale events.
     */
    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = SNACKBAR_BUFFER)
    public val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    /**
     * Initiate an outgoing DM 1:1 voice call. [channelId] must reference a [DmChannel]; group DM
     * is not yet supported (see architect report v2 §8.8). Catches [VoiceBusyException] and emits
     * a snackbar prompting the user to leave the current call first.
     */
    public fun startDmCall(channelId: ChannelId) {
        val client = voiceClient as? dev.puklic.voice.VoiceClient ?: return
        scope.launch {
            runCatching { client.connect(guildId = null, channelId = channelId) }
                .onFailure { t ->
                    Logger.w("MainViewModel", t) { "DM voice connect failed cid=$channelId" }
                    if (t is VoiceBusyException) {
                        _snackbarMessage.emit(SNACKBAR_LEAVE_CURRENT_CALL)
                    }
                }
        }
    }

    /**
     * Live queue of incoming DM calls — head is what [IncomingCallDialog] currently shows. Empty
     * list (and emptyFlow when no voice client) until a CALL_CREATE for this self user arrives.
     * See architect-report 2026-05-25-dm-incoming-voice §6.
     */
    public val incomingCalls: StateFlow<List<IncomingCall>> =
        (voiceClient as? VoiceClient)?.incomingCalls
            ?: MutableStateFlow<List<IncomingCall>>(emptyList()).asStateFlow()

    /**
     * Best-effort caller lookup against the persisted user cache. Returns null when the caller
     * id couldn't be resolved (the dialog renders a generic title in that case).
     */
    public suspend fun resolveCaller(callerId: UserId?): UserSummary? {
        if (callerId == null) return null
        return orchestrators?.user?.findById(callerId)
    }

    public fun acceptCall(channelId: ChannelId) {
        val client = voiceClient as? VoiceClient ?: return
        scope.launch {
            runCatching { client.acceptIncoming(channelId) }
                .onFailure { Logger.w("MainViewModel", it) { "acceptIncoming failed cid=$channelId" } }
        }
    }

    public fun declineCall(channelId: ChannelId) {
        val client = voiceClient as? VoiceClient ?: return
        scope.launch {
            runCatching { client.declineIncoming(channelId) }
                .onFailure { Logger.w("MainViewModel", it) { "declineIncoming failed cid=$channelId" } }
        }
    }

    /** Cancel a Connecting/Ringing/Connected voice session. Fire-and-forget. */
    public fun hangUpVoice() {
        val client = voiceClient as? dev.puklic.voice.VoiceClient ?: return
        scope.launch {
            runCatching { client.disconnect() }
                .onFailure { Logger.w("MainViewModel", it) { "voice disconnect failed" } }
        }
    }

    /**
     * Convenience accessors for the screen-share facade. The dock can also call
     * `voiceClient.screenShare` directly; these are provided for symmetry with the spec in
     * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §9 and for testability.
     */
    public val screenShareState: StateFlow<ScreenShareState>?
        get() = (voiceClient as? dev.puklic.voice.VoiceClient)?.screenShare?.state

    public suspend fun listScreenSources(): List<ScreenSource> =
        (voiceClient as? dev.puklic.voice.VoiceClient)?.screenShare?.listSources() ?: emptyList()

    public suspend fun startScreenShare(source: ScreenSource, shareAudio: Boolean) {
        (voiceClient as? dev.puklic.voice.VoiceClient)?.screenShare?.start(source, shareAudio)
    }

    public suspend fun stopScreenShare() {
        (voiceClient as? dev.puklic.voice.VoiceClient)?.screenShare?.stop()
    }

    /**
     * Live map of voice-channel-id → occupants. Surfaces the [voiceStates] repository so the
     * channel list can render a member list under each voice channel row. Empty map when no
     * session is wired (tests / pre-login).
     */
    public val voiceStates: StateFlow<Map<ChannelId, List<VoiceMember>>> =
        orchestrators?.voiceStates?.voiceStates
            ?: MutableStateFlow(emptyMap<ChannelId, List<VoiceMember>>()).asStateFlow()

    /**
     * Issue #81 — per-DM unread / mention view. Filtered passthrough of
     * [ReadStateOrchestrator.byChannel] keyed by DM channel ids. Empty map when there is no
     * orchestrator (tests) or no read-state pipeline wired yet.
     */
    public val dmUnread: StateFlow<Map<ChannelId, ReadStateView>> = run {
        val readState = orchestrators?.readState
        if (readState == null) {
            MutableStateFlow(emptyMap<ChannelId, ReadStateView>()).asStateFlow()
        } else {
            combine(orchestrators.dms.dms, readState.byChannel) { dms, rs ->
                computeDmUnread(dms, rs)
            }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }
    }

    /**
     * Issue #91 — per-channel unread / mention view for guild text channels. Passthrough of
     * [ReadStateOrchestrator.byChannel]; the channel list looks up entries by channel id, so no
     * per-guild filtering is needed. Empty map when there is no orchestrator (tests).
     */
    public val channelUnread: StateFlow<Map<ChannelId, ReadStateView>> =
        orchestrators?.readState?.byChannel
            ?: MutableStateFlow(emptyMap<ChannelId, ReadStateView>()).asStateFlow()

    /**
     * Issue #81 — per-guild "has a mention" flag for the rail dot. True iff any of the guild's
     * channels has [ReadStateView.mentionCount] > 0. The rail dot is mention-only by design
     * (plain unread is too noisy at the top-level rail).
     */
    public val guildHasMention: StateFlow<Map<GuildId, Boolean>> = run {
        val readState = orchestrators?.readState
        if (readState == null) {
            MutableStateFlow(emptyMap<GuildId, Boolean>()).asStateFlow()
        } else {
            orchestrators.guild.orderedGuilds
                .flatMapLatest { guilds ->
                    if (guilds.isEmpty()) {
                        flowOf(emptyMap<GuildId, Boolean>())
                    } else {
                        val perGuildChannels: List<kotlinx.coroutines.flow.Flow<Pair<GuildId, List<Channel>>>> =
                            guilds.map { g ->
                                orchestrators.channel.channelsForGuild(g.id).map { g.id to it }
                            }
                        val perGuildFlow: kotlinx.coroutines.flow.Flow<List<Pair<GuildId, List<Channel>>>> =
                            combine(perGuildChannels) { it.toList() }
                        combine(perGuildFlow, readState.byChannel) { perGuild, rs ->
                            perGuild.associate { (gid, channels) ->
                                val guildChannels = channels.filterIsInstance<GuildChannel>()
                                gid to guildHasMention(gid, guildChannels, rs)
                            }
                        }
                    }
                }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }
    }

    /**
     * Best-effort, non-suspending display name lookup used by voice-member rows. Falls back to
     * the numeric id when the user is not yet cached. ViewModel-scoped so the UI never touches
     * the persistence layer directly.
     */
    public suspend fun resolveDisplayName(userId: UserId): String {
        val user = orchestrators?.user?.findById(userId)
        return user?.globalName ?: user?.username ?: userId.value.toString()
    }

    /** Newest message id we have already MESSAGE_ACK'd per channel — de-dups redundant acks. */
    private val lastAcked = mutableMapOf<ChannelId, MessageId>()

    public fun selectChannel(id: ChannelId) {
        selectedChannel.value = id
        persistPosition()
        ackChannel(id)
        val gid = selectedGuild.value ?: return
        lazySubscribeChannel(gid, id)
    }

    /**
     * Mark [id] read at its newest known message (issue #91). The newest id is taken from the DM
     * list's `lastMessageId`; guild text channels do not carry it in the domain model yet, so they
     * are skipped here (their unread state is reconciled by the message-list layer in Slice 4).
     * De-dups via [shouldAck] against [lastAcked] and the server read marker so each genuinely-new
     * newest message is acked at most once.
     */
    private fun ackChannel(id: ChannelId) {
        val transport = sessionTransport ?: return
        val target = orchestrators?.dms?.dms?.value?.firstOrNull { it.id == id }?.lastMessageId ?: return
        val lastRead = orchestrators?.readState?.byChannel?.value?.get(id)?.lastReadMessageId
        if (!shouldAck(target, lastAcked[id], lastRead)) return
        lastAcked[id] = target
        scope.launch {
            runCatching { transport.markChannelRead(id, target) }
                .onFailure { Logger.w("MainViewModel", it) { "markChannelRead failed channel=${id.value}" } }
        }
    }

    private fun lazySubscribeGuildBootstrap(id: GuildId) {
        // Lazy-subscribe to the first text channels of this guild. Required by Discord user-mode
        // so REST `getMessages` stops returning 50001 on member-list-gated channels and so
        // GUILD_MEMBER_LIST_UPDATE events flow.
        val transport = sessionTransport ?: return
        val orch = orchestrators ?: return
        scope.launch {
            val channels = orch.channel.channelsForGuild(id).first()
            val textChannelIds = channels
                .filterIsInstance<GuildTextChannel>()
                .sortedBy { it.position }
                .take(LAZY_SUBSCRIBE_BOOTSTRAP)
                .map { it.id }
            if (textChannelIds.isNotEmpty()) {
                transport.lazyRequestGuild(id, textChannelIds)
            }
        }
    }

    private fun lazySubscribeChannel(gid: GuildId, cid: ChannelId) {
        // Re-subscribe with the focused channel so Discord lifts the 50001 gate before the next
        // REST `getMessages` issued by the messages orchestrator. A short settle delay gives
        // Discord time to register the subscription internally before REST runs — without it,
        // the gate may still be active and the first call returns 50001.
        val transport = sessionTransport ?: return
        scope.launch {
            transport.lazyRequestGuild(gid, listOf(cid))
            delay(LAZY_SUBSCRIBE_SETTLE_MS)
        }
    }

    private fun persistPosition() {
        if (preferences == null && secureStorage == null) return
        val current: LastPosition = when (val s = navScope.value) {
            NavigationScope.Empty -> LastPosition.Empty
            NavigationScope.DmHome -> LastPosition.DmHome(selectedChannel.value)
            is NavigationScope.GuildSelected -> LastPosition.Guild(s.id, selectedChannel.value)
        }
        scope.launch {
            runCatching { preferences?.setLastPosition(current) }
                .onFailure { Logger.w("MainViewModel", it) { "persistPosition (local) failed" } }
            runCatching { secureStorage?.put(LAST_POSITION_KEY, LastPositionCodec.encode(current)) }
                .onFailure { Logger.w("MainViewModel", it) { "persistPosition (synced) failed" } }
        }
    }

    public companion object {
        public const val LAST_POSITION_KEY: String = "discord.last_position"
        internal const val LAZY_SUBSCRIBE_BOOTSTRAP = 5
        const val LAZY_SUBSCRIBE_SETTLE_MS = 500L
        const val SNACKBAR_BUFFER = 4
        const val SNACKBAR_LEAVE_CURRENT_CALL = "Leave current call first"
        const val SNACKBAR_NEW_DM_FAILED = "Could not start the DM. Please try again."
        public const val ADD_FRIEND_UNAVAILABLE: String = "Add-friend is not available in this session."
        public const val ADD_FRIEND_EMPTY: String = "Enter a username, name#1234 or user id."
        public const val ADD_FRIEND_FAILED: String = "Friend request failed. Check the name and try again."
        public const val ADD_FRIEND_SUCCESS: String = "Friend request sent."
        public const val JOIN_SERVER_UNAVAILABLE: String = "Join-server is not available in this session."
        public const val JOIN_SERVER_EMPTY: String = "Enter an invite link or code."
        public const val JOIN_SERVER_FAILED: String = "Could not join the server. Check the invite and try again."
        public const val JOIN_SERVER_SUCCESS: String = "Joined."
    }
}

/**
 * Parse an Add-Friend free-text field into `(username, discriminator?)`. Strips a leading `@`,
 * splits on `#` for the legacy `name#1234` form (only when the part after `#` is all digits),
 * and otherwise returns the input as a pomelo username with a null discriminator.
 */
internal fun parseAddFriendQuery(raw: String): Pair<String, String?> {
    val trimmed = raw.trim().removePrefix("@")
    val hash = trimmed.indexOf('#')
    if (hash in 1 until trimmed.lastIndex) {
        val name = trimmed.substring(0, hash)
        val discrim = trimmed.substring(hash + 1)
        if (discrim.isNotEmpty() && discrim.all { it.isDigit() }) {
            return name to discrim
        }
    }
    return trimmed to null
}

/**
 * Extract a Discord invite code from a raw input. Accepts a bare code, `discord.gg/<code>`,
 * `https://discord.gg/<code>`, `https://discord.com/invite/<code>`, or the discordapp.com
 * equivalent. Trailing slashes and query strings are stripped.
 */
internal fun parseInviteCode(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val withoutScheme = trimmed
        .removePrefix("https://")
        .removePrefix("http://")
    val candidate = when {
        withoutScheme.startsWith("discord.gg/") -> withoutScheme.removePrefix("discord.gg/")
        withoutScheme.startsWith("discord.com/invite/") -> withoutScheme.removePrefix("discord.com/invite/")
        withoutScheme.startsWith("discordapp.com/invite/") -> withoutScheme.removePrefix("discordapp.com/invite/")
        else -> withoutScheme
    }
    return candidate.substringBefore('/').substringBefore('?').trim()
}
