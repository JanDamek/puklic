package dev.puklic.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import dev.puklic.ui.components.voice.DOWNGRADE_BANNER_AUTO_HIDE_MS
import dev.puklic.ui.components.voice.DaveDowngradeBanner
import dev.puklic.ui.components.voice.IncomingVideoPane
import dev.puklic.ui.components.voice.ScreenSharePickerDialog
import dev.puklic.ui.components.voice.VerifyCallDialog
import dev.puklic.ui.components.voice.VoiceSettingsDialog
import dev.puklic.ui.components.voice.VoiceStatusBar
import dev.puklic.voice.DaveDowngradeDetector
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import dev.puklic.voice.screenshare.ScreenShareState
import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.screenshare.source.BlackholeDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal actual fun VoiceDock(viewModel: MainViewModel) {
    val voiceClient = viewModel.voiceClient as? VoiceClient ?: return
    val state by voiceClient.state.collectAsState()
    val devices by voiceClient.devices.collectAsState()
    val screenShareState by voiceClient.screenShare.state.collectAsState()
    val incomingVideo by voiceClient.incomingVideo.collectAsState()
    val daveState by voiceClient.daveState.collectAsState()
    val participants by voiceClient.participants.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var verifyCallOpen by remember { mutableStateOf(false) }
    var downgradeBannerVisible by remember { mutableStateOf(false) }
    var previousDaveState by remember { mutableStateOf<DaveUiState>(DaveUiState.Off) }

    // Reset the dismissed-banner state when the call ends so a fresh call
    // gets a fresh chance to show downgrade events.
    LaunchedEffect(state) {
        if (state is VoiceState.Idle) {
            downgradeBannerVisible = false
            previousDaveState = DaveUiState.Off
        }
    }

    // Detect Active -> {Disabled, Off} transitions and surface the banner.
    LaunchedEffect(daveState) {
        if (DaveDowngradeDetector.isDowngrade(previousDaveState, daveState)) {
            downgradeBannerVisible = true
        }
        previousDaveState = daveState
    }

    // Auto-hide the banner after the configured timeout.
    LaunchedEffect(downgradeBannerVisible) {
        if (downgradeBannerVisible) {
            delay(DOWNGRADE_BANNER_AUTO_HIDE_MS)
            downgradeBannerVisible = false
        }
    }
    var pickerSources by remember { mutableStateOf<List<ScreenSource>>(emptyList()) }
    var blackholeAvailable by remember { mutableStateOf(false) }
    var selectedCaptureId by remember { mutableStateOf<String?>(null) }
    var selectedPlaybackId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val label = (state as? VoiceState.Connected)?.let { "voice" }

    Column {
        if (downgradeBannerVisible) {
            DaveDowngradeBanner(onDismiss = { downgradeBannerVisible = false })
        }
        IncomingVideoPane(frames = incomingVideo)
        VoiceStatusBar(
        state = state,
        channelLabel = label,
        onMicToggle = {
            val current = state
            if (current is VoiceState.Connected) voiceClient.setSelfMute(!current.selfMute)
        },
        onDeafToggle = {
            val current = state
            if (current is VoiceState.Connected) voiceClient.setSelfDeaf(!current.selfDeaf)
        },
        onDisconnect = { scope.launch { voiceClient.disconnect() } },
        onSettings = { settingsOpen = true },
        onRetry = { scope.launch { voiceClient.disconnect() } },
        screenShareState = screenShareState,
        onScreenSharePick = {
            scope.launch {
                pickerSources = voiceClient.screenShare.listSources()
                blackholeAvailable = runCatching { BlackholeDetector.isAvailable() }.getOrDefault(false)
                pickerOpen = true
            }
        },
        onScreenShareStop = { scope.launch { voiceClient.screenShare.stop() } },
        daveState = daveState,
        onVerifyCall = { verifyCallOpen = true },
        )
    }

    if (verifyCallOpen) {
        VerifyCallDialog(
            participants = participants,
            sasResolver = { uid -> voiceClient.pairwiseFingerprint(uid) },
            onDismiss = { verifyCallOpen = false },
        )
    }

    if (settingsOpen) {
        VoiceSettingsDialog(
            devices = devices,
            selectedCaptureId = selectedCaptureId,
            selectedPlaybackId = selectedPlaybackId,
            onSelectCapture = { id ->
                selectedCaptureId = id
                voiceClient.selectCaptureDevice(id)
            },
            onSelectPlayback = { id ->
                selectedPlaybackId = id
                voiceClient.selectPlaybackDevice(id)
            },
            onDismiss = { settingsOpen = false },
        )
    }

    if (pickerOpen && screenShareState !is ScreenShareState.Active) {
        ScreenSharePickerDialog(
            sources = pickerSources,
            blackholeAvailable = blackholeAvailable,
            onShare = { source, shareAudio ->
                pickerOpen = false
                scope.launch { voiceClient.screenShare.start(source, shareAudio) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
