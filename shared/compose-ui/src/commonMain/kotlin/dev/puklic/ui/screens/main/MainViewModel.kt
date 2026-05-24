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
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.LastPosition
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.domain.UserSummary
import dev.puklic.repositories.Orchestrators
import dev.puklic.repositories.PresenceState
import dev.puklic.session.SessionManager
import dev.puklic.session.SessionTransport
import dev.puklic.ui.screens.settings.SettingsCategory
import dev.puklic.voice.DaveUiState
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    initialPosition: LastPosition = LastPosition.Empty,
    public val voiceClient: Any? = null,
    private val sessionManager: SessionManager? = null,
) : ComponentContext by componentContext {

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
     * Best-effort, non-suspending display name lookup used by voice-member rows. Falls back to
     * the numeric id when the user is not yet cached. ViewModel-scoped so the UI never touches
     * the persistence layer directly.
     */
    public suspend fun resolveDisplayName(userId: UserId): String {
        val user = orchestrators?.user?.findById(userId)
        return user?.globalName ?: user?.username ?: userId.value.toString()
    }

    public fun selectChannel(id: ChannelId) {
        selectedChannel.value = id
        persistPosition()
        val gid = selectedGuild.value ?: return
        lazySubscribeChannel(gid, id)
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
        val prefs = preferences ?: return
        val current: LastPosition = when (val s = navScope.value) {
            NavigationScope.Empty -> LastPosition.Empty
            NavigationScope.DmHome -> LastPosition.DmHome(selectedChannel.value)
            is NavigationScope.GuildSelected -> LastPosition.Guild(s.id, selectedChannel.value)
        }
        scope.launch {
            runCatching { prefs.setLastPosition(current) }
                .onFailure { Logger.w("MainViewModel", it) { "persistPosition failed" } }
        }
    }

    private companion object {
        const val LAZY_SUBSCRIBE_BOOTSTRAP = 5
        const val LAZY_SUBSCRIBE_SETTLE_MS = 500L
        const val SNACKBAR_BUFFER = 4
        const val SNACKBAR_LEAVE_CURRENT_CALL = "Leave current call first"
    }
}
