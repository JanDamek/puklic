package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.DmChannel
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val LOG_TAG = "NotificationDispatcher"
private const val MAX_BODY_LEN = 200

/**
 * Issue #13 — desktop notifications.
 *
 * Session-scoped service that subscribes to [GatewayEventSource] for
 * [GatewayDomainEvent.MessageCreated] and forwards to the per-platform
 * [NotificationService] when the message warrants a user notification.
 *
 * Filter rules (Phase 1.1, no mute gate yet — deferred to NotificationSettings slice):
 *  - Self-user exclusion: never notify on the current user's own messages.
 *  - DM or mention rule: notify if the message is in a DM channel, or if the
 *    user is @-mentioned (by user id), or if `@everyone`/`@here` is present
 *    (the gateway exposes both via [dev.puklic.domain.MessageMentions.everyone]).
 *
 * Channel kind is resolved via [ChannelRepository.findById]; on cache miss
 * we conservatively skip notification rather than spam.
 *
 * Per architect-report 2026-05-24-notifications.md §3 (slice 2).
 */
public class NotificationDispatcher(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val channelRepository: ChannelRepository,
    private val notificationService: NotificationService,
    private val selfUserIdProvider: () -> UserId?,
) {
    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent.MessageCreated>()
            .onEach { handle(it.message) }
            .launchIn(sessionScope)
    }

    private suspend fun handle(message: ChatMessage) {
        val self = selfUserIdProvider() ?: return
        if (message.author.id == self) return
        if (!shouldNotify(message, self)) return
        sessionScope.launch {
            runCatching { notificationService.show(buildNotification(message)) }
                .onFailure { ex ->
                    Logger.w(LOG_TAG, ex) { "Failed to dispatch notification for ${message.id.value}" }
                }
        }
    }

    private suspend fun shouldNotify(message: ChatMessage, self: UserId): Boolean {
        if (message.mentions.users.contains(self)) return true
        if (message.mentions.everyone) return true
        // Otherwise: notify only if this is a DM channel.
        val channel = runCatching { channelRepository.findById(message.channelId) }.getOrNull()
        return channel is DmChannel
    }

    private fun buildNotification(message: ChatMessage): Notification {
        val title = message.author.username
        val body = message.rawContent.take(MAX_BODY_LEN)
        return Notification(
            title = title,
            body = body,
            iconPath = null,
            actions = emptyList(),
            tag = "msg-${message.channelId.value}",
            urgent = false,
        )
    }
}
