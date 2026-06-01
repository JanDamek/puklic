package dev.puklic.repositories

import dev.puklic.domain.Guild
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private fun guild(id: Long, name: String = "g$id") = Guild(
    id = GuildId(id),
    name = name,
    iconHash = null,
    ownerId = UserId(1L),
    features = emptySet(),
    memberCount = null,
)

private fun textChannel(id: Long, guildId: Long, name: String = "c$id") = GuildTextChannel(
    id = ChannelId(id),
    name = name,
    guildId = GuildId(guildId),
    parentId = null,
    topic = null,
    position = 0,
    rateLimitPerUser = 0,
    nsfw = false,
)

private fun user(id: Long, name: String = "u$id") = UserSummary(
    id = UserId(id),
    username = name,
    globalName = null,
    discriminator = null,
    avatarHash = null,
    bot = false,
    system = false,
)

class GuildOrchestratorTest {
    @Test
    fun guild_created_event_is_persisted_and_observed() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeGuildRepository()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = GuildOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.GuildCreated(guild(42L, "Acme")))
        testScheduler.runCurrent()

        val observed = orch.guilds.value
        observed.size shouldBe 1
        observed.first().id shouldBe GuildId(42L)
        observed.first().name shouldBe "Acme"
        job.cancel()
    }

    @Test
    fun guild_positions_event_orders_guilds_by_user_settings() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeGuildRepository()
        storage.persist(guild(1L, "Charlie"))
        storage.persist(guild(2L, "Alpha"))
        storage.persist(guild(3L, "Bravo"))
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = GuildOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        // user-ordered: 3, 1 (2 is missing -> appended by name)
        gw.emit(GatewayDomainEvent.GuildPositionsUpdated(listOf(GuildId(3L), GuildId(1L))))
        testScheduler.runCurrent()

        val ordered = orch.orderedGuilds.value
        ordered.map { it.id.value } shouldBe listOf(3L, 1L, 2L)
        job.cancel()
    }

    @Test
    fun apply_ordering_falls_back_to_name_when_positions_empty() {
        val gs = listOf(guild(1L, "Zulu"), guild(2L, "Alpha"), guild(3L, "Mike"))
        val ordered = GuildOrchestrator.applyOrdering(gs, emptyList())
        ordered.map { it.name } shouldBe listOf("Alpha", "Mike", "Zulu")
    }

    @Test
    fun apply_ordering_appends_unknown_guilds_after_positioned_ones() {
        val gs = listOf(
            guild(1L, "One"),
            guild(2L, "Two"),
            guild(3L, "Three"),
            guild(4L, "Four"),
        )
        val ordered = GuildOrchestrator.applyOrdering(gs, listOf(GuildId(3L), GuildId(1L)))
        ordered.map { it.id.value } shouldBe listOf(3L, 1L, 4L, 2L)
    }

    @Test
    fun guild_deleted_event_removes_guild() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeGuildRepository()
        storage.persist(guild(7L))
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = GuildOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.GuildDeleted(GuildId(7L)))
        testScheduler.runCurrent()

        orch.guilds.value shouldBe emptyList()
        job.cancel()
    }
}

class ChannelOrchestratorTest {
    @Test
    fun channel_created_event_appears_in_guild_flow() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = ChannelOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(101L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(102L, guildId = 99L)))
        testScheduler.runCurrent()

        val list = orch.channelsForGuild(GuildId(5L)).first()
        list.size shouldBe 1
        list.first().id shouldBe ChannelId(101L)
        job.cancel()
    }

    @Test
    fun channel_deleted_removes_channel() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        storage.persist(textChannel(101L, guildId = 5L))
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = ChannelOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelDeleted(ChannelId(101L)))
        testScheduler.runCurrent()

        orch.channelsForGuild(GuildId(5L)).first() shouldBe emptyList()
        job.cancel()
    }
}

class UserOrchestratorTest {
    @Test
    fun ready_event_captures_self_user_and_persists() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeUserRepository()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = UserOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.Ready(selfUser = user(1L, "me"), sessionId = "sess"))
        testScheduler.runCurrent()

        orch.selfUser.value?.id shouldBe UserId(1L)
        orch.findById(UserId(1L))?.username shouldBe "me"
        job.cancel()
    }

    @Test
    fun ready_event_persists_top_level_users_array() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeUserRepository()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        UserOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        val bulk = listOf(user(2L, "alice"), user(3L, "bob"), user(1L, "me-dup"))
        gw.emit(GatewayDomainEvent.Ready(selfUser = user(1L, "me"), sessionId = "sess", users = bulk))
        testScheduler.runCurrent()

        storage.findById(UserId(1L))?.username shouldBe "me"
        storage.findById(UserId(2L))?.username shouldBe "alice"
        storage.findById(UserId(3L))?.username shouldBe "bob"
        // self user not duplicated via persistAll
        storage.persistedAll.flatten().none { it.id == UserId(1L) } shouldBe true
        job.cancel()
    }

    @Test
    fun user_updated_event_refreshes_self_user_when_id_matches() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeUserRepository()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = UserOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.Ready(selfUser = user(1L, "me"), sessionId = "sess"))
        testScheduler.runCurrent()
        gw.emit(GatewayDomainEvent.UserUpdated(user(1L, "me-renamed")))
        testScheduler.runCurrent()

        orch.selfUser.value?.username shouldBe "me-renamed"
        job.cancel()
    }
}
