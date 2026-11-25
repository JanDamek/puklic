package dev.puklic.platform.android.voice

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.platform.PlatformDenied
import dev.puklic.platform.android.RuntimePermissions
import dev.puklic.voice.VoiceClient

/**
 * Asks for the microphone (and, on Android 13+, notification) permission the first time the user joins
 * a voice channel, instead of at startup, and delegates everything else to [engine]. Joining is refused
 * with [PlatformDenied] when the microphone permission is not granted; a refused notification
 * permission only hides the foreground notification and does not block the call.
 */
class PermissionGatedVoiceClient(
    private val engine: VoiceClient,
    private val permissions: RuntimePermissions,
) : VoiceClient by engine {

    override suspend fun connect(guildId: GuildId?, channelId: ChannelId) {
        ensureVoicePermissions()
        engine.connect(guildId, channelId)
    }

    override suspend fun acceptIncoming(channelId: ChannelId) {
        ensureVoicePermissions()
        engine.acceptIncoming(channelId)
    }

    private suspend fun ensureVoicePermissions() {
        if (!permissions.ensureGranted(RuntimePermissions.MICROPHONE)) {
            throw PlatformDenied("Microphone permission is required to join a voice channel")
        }
        permissions.ensureGranted(RuntimePermissions.NOTIFICATIONS)
    }
}
