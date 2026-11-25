package dev.puklic.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import kotlinx.coroutines.launch

/**
 * Android `actual` for [VoiceDock]: one compact row at the bottom of the channel list, shown only
 * while a voice session is in progress. It carries the channel and server name plus mute, deafen and
 * disconnect. Screen sharing and the DAVE indicator are not offered because the Android engine
 * supports neither.
 */
@Composable
internal actual fun VoiceDock(viewModel: MainViewModel) {
    val voiceClient = viewModel.voiceClient as? VoiceClient ?: return
    val state by voiceClient.state.collectAsState()
    val screenState by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val label = rememberVoiceChannelLabel(state.voiceChannelId(), screenState)
    AndroidVoiceBar(
        state = state,
        label = label,
        actions = VoiceBarActions(
            onMuteToggle = { (state as? VoiceState.Connected)?.let { voiceClient.setSelfMute(!it.selfMute) } },
            onDeafenToggle = { (state as? VoiceState.Connected)?.let { voiceClient.setSelfDeaf(!it.selfDeaf) } },
            onDisconnect = { scope.launch { voiceClient.disconnect() } },
        ),
    )
}
