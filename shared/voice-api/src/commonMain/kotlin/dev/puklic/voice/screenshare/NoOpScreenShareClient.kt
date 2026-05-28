package dev.puklic.voice.screenshare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No-op [ScreenShareClient] used by [dev.puklic.voice.NoOpVoiceClient] and as the default
 * binding inside [dev.puklic.voice.DefaultVoiceClient] before voice is connected.
 */
public class NoOpScreenShareClient : ScreenShareClient {
    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    override val state: StateFlow<ScreenShareState> = _state.asStateFlow()
    override suspend fun listSources(): List<ScreenSource> = emptyList()
    override suspend fun start(source: ScreenSource, shareAudio: Boolean) { /* no-op */ }
    override suspend fun stop() { /* no-op */ }
}
