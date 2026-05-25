package dev.puklic.repositories

import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDispatcherVisibilityTest {
    private val selfId = UserId(1001L)
    private val otherId = UserId(2002L)
    private val guildChannelId = ChannelId(600L)
    private val dmChannelId = ChannelId(500L)
    private val guildId = GuildId(700L)

    private fun guildTextChannel() = GuildTextChannel(
        id = guildChannelId,
        name = "general",
        guildId = guildId,
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    @Test
    fun `mention in non-viewable guild channel is suppressed`() = runTest(UnconfinedTestDispatcher()) {
        val events = FakeGatewayEventSource()
        val channels = FakeChannelRepository()
        val notifications = RecordingNotificationService()
        channels.persist(guildTextChannel())
        // Visibility check explicitly hides the channel.
        val visibility = VisibilityCheck { ch -> if (ch == guildChannelId) false else null }
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = events,
            channelRepository = channels,
            notificationService = notifications,
            selfUserIdProvider = { selfId },
            visibilityCheck = visibility,
        )
        testScheduler.advanceUntilIdle()

        val mentions = MessageMentions(listOf(selfId), emptyList(), emptyList(), everyone = false)
        events.emit(GatewayDomainEvent.MessageCreated(
            message(id = 10L, channelId = guildChannelId, ts = 10L).copy(
                author = UserSummary(otherId, "other", null, null, null, bot = false, system = false),
                mentions = mentions,
            ),
        ))
        testScheduler.advanceUntilIdle()
        notifications.shown.shouldBeEmpty()
    }

    @Test
    fun `mention in visible channel notifies`() = runTest(UnconfinedTestDispatcher()) {
        val events = FakeGatewayEventSource()
        val channels = FakeChannelRepository()
        val notifications = RecordingNotificationService()
        channels.persist(guildTextChannel())
        val visibility = VisibilityCheck { _ -> true }
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = events,
            channelRepository = channels,
            notificationService = notifications,
            selfUserIdProvider = { selfId },
            visibilityCheck = visibility,
        )
        testScheduler.advanceUntilIdle()

        events.emit(GatewayDomainEvent.MessageCreated(
            message(id = 11L, channelId = guildChannelId, ts = 11L).copy(
                author = UserSummary(otherId, "other", null, null, null, bot = false, system = false),
                mentions = MessageMentions(listOf(selfId), emptyList(), emptyList(), everyone = false),
            ),
        ))
        testScheduler.advanceUntilIdle()
        notifications.shown shouldHaveSize 1
    }

    @Test
    fun `unknown visibility null suppresses guild channel notification`() = runTest(UnconfinedTestDispatcher()) {
        val events = FakeGatewayEventSource()
        val channels = FakeChannelRepository()
        val notifications = RecordingNotificationService()
        channels.persist(guildTextChannel())
        val visibility = VisibilityCheck { _ -> null }
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = events,
            channelRepository = channels,
            notificationService = notifications,
            selfUserIdProvider = { selfId },
            visibilityCheck = visibility,
        )
        testScheduler.advanceUntilIdle()

        events.emit(GatewayDomainEvent.MessageCreated(
            message(id = 12L, channelId = guildChannelId, ts = 12L).copy(
                author = UserSummary(otherId, "other", null, null, null, bot = false, system = false),
                mentions = MessageMentions(listOf(selfId), emptyList(), emptyList(), everyone = false),
            ),
        ))
        testScheduler.advanceUntilIdle()
        notifications.shown.shouldBeEmpty()
    }

    @Test
    fun `DM channel always notifies regardless of visibility check`() = runTest(UnconfinedTestDispatcher()) {
        val events = FakeGatewayEventSource()
        val channels = FakeChannelRepository()
        val notifications = RecordingNotificationService()
        channels.persist(DmChannel(id = dmChannelId, recipients = emptyList()))
        // visibility says null (no data) — DM short-circuit must still notify.
        val visibility = VisibilityCheck { _ -> null }
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = events,
            channelRepository = channels,
            notificationService = notifications,
            selfUserIdProvider = { selfId },
            visibilityCheck = visibility,
        )
        testScheduler.advanceUntilIdle()

        events.emit(GatewayDomainEvent.MessageCreated(
            message(id = 13L, channelId = dmChannelId, ts = 13L).copy(
                author = UserSummary(otherId, "other", null, null, null, bot = false, system = false),
            ),
        ))
        testScheduler.advanceUntilIdle()
        notifications.shown shouldHaveSize 1
    }
}
