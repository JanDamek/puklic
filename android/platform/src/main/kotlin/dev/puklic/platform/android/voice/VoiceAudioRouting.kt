package dev.puklic.platform.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Puts the device into the communication audio mode for the duration of a voice session: it takes
 * audio focus, switches `AudioManager` to `MODE_IN_COMMUNICATION` (which also enables the platform echo
 * canceller for the call) and routes the call to a connected headset, falling back to the loudspeaker.
 * [acquire] and [release] are idempotent and are driven by [VoiceServiceController].
 */
class VoiceAudioRouting(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null

    fun acquire() {
        if (previousMode != null) return
        previousMode = audioManager.mode
        focusRequest = buildFocusRequest().also { audioManager.requestAudioFocus(it) }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToBestDevice()
    }

    fun release() {
        val restoreMode = previousMode ?: return
        clearRoute()
        audioManager.mode = restoreMode
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        previousMode = null
    }

    private fun buildFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()

    private fun routeToBestDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val target = devices.firstOrNull { it.type in HEADSET_TYPES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            target?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !audioManager.isHeadsetConnected()
        }
    }

    private fun clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun AudioManager.isHeadsetConnected(): Boolean =
        getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    private companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
