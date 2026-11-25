package dev.puklic.platform.android.voice

/**
 * Implemented by the `Application`: lets [VoiceForegroundService] end the active voice session when
 * the user taps "Disconnect" in the foreground notification, without the service knowing the session graph.
 */
interface VoiceDisconnectHandler {
    fun disconnectVoice()
}
