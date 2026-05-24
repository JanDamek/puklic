package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDispatcherTest {
    private val selfId = UserId(1001L)
    private val otherId = UserId(2002L)
    private val dmChannelId = ChannelId(500L)
    private val guildChannelId = ChannelId(600L)
    private val guildId = GuildId(700L)

    private fun setup(): Setup = Setup(
        events = FakeGatewayEventSource(),
        channels = FakeChannelRepository(),
        notifications = RecordingNotificationService(),
    )

    private data class Setup(
        val events: FakeGatewayEventSource,
        val channels: FakeChannelRepository,
        val notifications: RecordingNotificationService,
    )

    @Test
    fun `DM message from other user notifies once`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(DmChannel(id = dmChannelId, recipients = emptyList()))
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(dmMessage(authorId = otherId, content = "hi")))
        testScheduler.advanceUntilIdle()

        s.notifications.shown shouldHaveSize 1
        s.notifications.shown.first().body shouldBe "hi"
    }

    @Test
    fun `channel message without mention is not notified`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(guildTextChannel())
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(channelMessage(authorId = otherId)))
        testScheduler.advanceUntilIdle()

        s.notifications.shown.shouldBeEmpty()
    }

    @Test
    fun `channel message mentioning self is notified`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(guildTextChannel())
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        val msg = channelMessage(
            authorId = otherId,
            mentions = MessageMentions(
                users = listOf(selfId),
                roles = emptyList(),
                channels = emptyList(),
                everyone = false,
            ),
        )
        s.events.emit(GatewayDomainEvent.MessageCreated(msg))
        testScheduler.advanceUntilIdle()

        s.notifications.shown shouldHaveSize 1
    }

    @Test
    fun `channel message with everyone is notified`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(guildTextChannel())
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        val msg = channelMessage(
            authorId = otherId,
            mentions = MessageMentions(
                users = emptyList(),
                roles = emptyList(),
                channels = emptyList(),
                everyone = true,
            ),
        )
        s.events.emit(GatewayDomainEvent.MessageCreated(msg))
        testScheduler.advanceUntilIdle()

        s.notifications.shown shouldHaveSize 1
    }

    @Test
    fun `own message never notifies`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(DmChannel(id = dmChannelId, recipients = emptyList()))
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(dmMessage(authorId = selfId, content = "me")))
        testScheduler.advanceUntilIdle()

        s.notifications.shown.shouldBeEmpty()
    }

    @Test
    fun `null selfUserId before READY skips notification`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(DmChannel(id = dmChannelId, recipients = emptyList()))
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { null },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(dmMessage(authorId = otherId)))
        testScheduler.advanceUntilIdle()

        s.notifications.shown.shouldBeEmpty()
    }

    @Test
    fun `two consecutive DM messages notify twice`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.channels.persist(DmChannel(id = dmChannelId, recipients = emptyList()))
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(dmMessage(authorId = otherId, content = "one", id = 1L)))
        s.events.emit(GatewayDomainEvent.MessageCreated(dmMessage(authorId = otherId, content = "two", id = 2L)))
        testScheduler.advanceUntilIdle()

        s.notifications.shown shouldHaveSize 2
    }

    @Test
    fun `unknown channel kind defaults to no notification`() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        // No channel persisted for guildChannelId — findById returns null.
        NotificationDispatcher(
            sessionScope = backgroundScope,
            gatewaySource = s.events,
            channelRepository = s.channels,
            notificationService = s.notifications,
            selfUserIdProvider = { selfId },
        )
        testScheduler.advanceUntilIdle()

        s.events.emit(GatewayDomainEvent.MessageCreated(channelMessage(authorId = otherId)))
        testScheduler.advanceUntilIdle()

        s.notifications.shown.shouldBeEmpty()
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun guildTextChannel(): GuildTextChannel = GuildTextChannel(
        id = guildChannelId,
        name = "general",
        guildId = guildId,
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    private fun dmMessage(
        authorId: UserId,
        content: String = "hello",
        id: Long = 1L,
    ): ChatMessage = message(
        id = id,
        channelId = dmChannelId,
        ts = id,
        content = content,
        authorId = authorId.value,
    ).let { base ->
        base.copy(author = UserSummary(authorId, "user-${authorId.value}", null, null, null, bot = false, system = false))
    }

    private fun channelMessage(
        authorId: UserId,
        content: String = "hello",
        id: Long = 10L,
        mentions: MessageMentions = MessageMentions(emptyList(), emptyList(), emptyList(), everyone = false),
    ): ChatMessage = message(
        id = id,
        channelId = guildChannelId,
        ts = id,
        content = content,
        authorId = authorId.value,
    ).copy(
        author = UserSummary(authorId, "user-${authorId.value}", null, null, null, bot = false, system = false),
        mentions = mentions,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun unusedRole(): RoleId = RoleId(0L)
}

internal class RecordingNotificationService : NotificationService {
    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = false)

    val shown: MutableList<Notification> = mutableListOf()
    private var nextId = 0

    override suspend fun show(notification: Notification): NotificationHandle {
        shown += notification
        return NotificationHandle("test-${nextId++}")
    }

    override suspend fun cancel(handle: NotificationHandle) = Unit
}
