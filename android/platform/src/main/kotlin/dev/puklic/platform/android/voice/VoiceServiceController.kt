package dev.puklic.platform.android.voice

import android.content.Context
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val LOG_TAG = "VoiceServiceController"

/**
 * Runs [VoiceForegroundService] and the communication audio route ([VoiceAudioRouting]) exactly while a
 * voice session is in progress. The service starts when
 * the client leaves idle (connecting, ringing or connected, a transition the user triggers while the
 * app is in the foreground, which is when Android allows a foreground service to start) and stops
 * when the session ends, fails or the owning [scope] is cancelled.
 */
class VoiceServiceController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val audioRouting = VoiceAudioRouting(context)

    fun bind(client: VoiceClient): Job = scope.launch {
        try {
            client.state
                .map(VoiceState::isSessionActive)
                .distinctUntilChanged()
                .collect { active -> if (active) start() else stop() }
        } finally {
            stop()
        }
    }

    private fun start() {
        audioRouting.acquire()
        runCatching { ContextCompat.startForegroundService(context, VoiceForegroundService.startIntent(context)) }
            .onFailure { Logger.w(LOG_TAG, it) { "Cannot start the voice foreground service" } }
    }

    private fun stop() {
        audioRouting.release()
        context.stopService(VoiceForegroundService.startIntent(context))
    }
}

/** True while a voice session is connecting, ringing or connected, the span the voice foreground service runs. */
fun VoiceState.isSessionActive(): Boolean = when (this) {
    is VoiceState.Connecting, is VoiceState.Ringing, is VoiceState.Connected -> true
    is VoiceState.Idle, is VoiceState.Failed -> false
}
