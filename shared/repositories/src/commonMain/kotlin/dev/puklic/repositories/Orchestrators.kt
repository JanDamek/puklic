package dev.puklic.repositories

/**
 * Bundle of orchestrators owned by a [dev.puklic.session.DiscordSession]. Each session creates one
 * instance; all members share the same session [kotlinx.coroutines.CoroutineScope].
 */
public data class Orchestrators(
    val messages: MessageOrchestrator,
    val outboundWorker: OutboundMessageWorker,
    val presence: PresenceOrchestrator,
    val typing: TypingOrchestrator,
    val guild: GuildOrchestrator,
    val channel: ChannelOrchestrator,
    val user: UserOrchestrator,
)
