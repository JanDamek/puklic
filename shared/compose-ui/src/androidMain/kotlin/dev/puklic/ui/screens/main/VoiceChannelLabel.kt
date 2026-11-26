package dev.puklic.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildChannel
import dev.puklic.ids.ChannelId
import dev.puklic.voice.VoiceState
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.voice_direct_call
import dev.puklic.ui.text.UiText

/** What the voice bar shows on its left: the channel and, for guild channels, the server name. */
internal data class VoiceChannelLabel(val channelName: UiText, val serverName: String?)

internal fun VoiceState.voiceChannelId(): ChannelId? = when (this) {
    is VoiceState.Connecting -> channelId
    is VoiceState.Ringing -> channelId
    is VoiceState.Connected -> channelId
    is VoiceState.Idle, is VoiceState.Failed -> null
}

/**
 * Resolves the label of [channelId] from the current screen state and remembers it for the lifetime of
 * the call, so it survives the user switching to another server while still in the voice channel.
 */
@Composable
internal fun rememberVoiceChannelLabel(channelId: ChannelId?, screenState: MainScreenState): VoiceChannelLabel? {
    var remembered by remember(channelId) { mutableStateOf<VoiceChannelLabel?>(null) }
    channelId ?: return null
    screenState.labelFor(channelId)?.let { remembered = it }
    return remembered
}

private fun MainScreenState.labelFor(channelId: ChannelId): VoiceChannelLabel? {
    val guildChannel = channelsForSelectedGuild.firstOrNull { it.id == channelId } as? GuildChannel
    if (guildChannel != null) {
        val serverName = guilds.firstOrNull { it.id == guildChannel.guildId }?.name
        return VoiceChannelLabel(channelName = UiText.Verbatim(guildChannel.name.orEmpty()), serverName = serverName)
    }
    val directChannel = dmChannels.firstOrNull { it.id == channelId } ?: return null
    return VoiceChannelLabel(channelName = directChannel.callTitle(), serverName = null)
}

private fun DmChannel.callTitle(): UiText =
    recipients.firstOrNull()?.let { UiText.Verbatim(it.globalName ?: it.username) }
        ?: UiText.of(Res.string.voice_direct_call)
