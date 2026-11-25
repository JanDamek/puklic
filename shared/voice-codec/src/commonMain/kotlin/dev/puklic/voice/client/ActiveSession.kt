package dev.puklic.voice.client

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.gateway.VoiceGatewayConnection
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.Volatile

/** Resources of the voice call currently in progress; discarded as a whole when it ends. */
@PuklicVoiceCodec
internal class ActiveSession(
    val guildId: GuildId?,
    val channelId: ChannelId,
    val scope: CoroutineScope,
) {
    @Volatile
    var gateway: VoiceGatewayConnection? = null

    @Volatile
    var media: VoiceMediaSession? = null

    @Volatile
    var ssrc: Int = 0
}
