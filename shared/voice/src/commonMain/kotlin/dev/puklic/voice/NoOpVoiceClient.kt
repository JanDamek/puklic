package dev.puklic.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.voice.screenshare.NoOpScreenShareClient
import dev.puklic.voice.screenshare.ScreenShareClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [VoiceClient] used until later slices replace it with the real implementation
 * (slices 2–10 per architect report §13). Always reports [VoiceState.Idle] and an empty
 * device list; all mutators are no-ops.
 *
 * Wired into [dev.puklic.session.DiscordSession] when the `puklic.voice.enabled` feature
 * flag is false (default during Phase 3.0 development).
 */
public class NoOpVoiceClient : VoiceClient {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    override val screenShare: ScreenShareClient = NoOpScreenShareClient()

    override suspend fun connect(guildId: GuildId, channelId: ChannelId) { /* no-op */ }
    override suspend fun disconnect() { /* no-op */ }
    override fun setSelfMute(muted: Boolean) { /* no-op */ }
    override fun setSelfDeaf(deaf: Boolean) { /* no-op */ }
    override fun selectCaptureDevice(id: String) { /* no-op */ }
    override fun selectPlaybackDevice(id: String) { /* no-op */ }
}
