package dev.puklic.repositories

import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.Member
import dev.puklic.domain.OverwriteType
import dev.puklic.domain.PermissionOverwrite
import dev.puklic.domain.Role
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * End-to-end tests for the issue #18 visibility filter wired through ChannelOrchestrator.
 * See architect-report 2026-05-24-channel-permission-design.md §11.
 */
class ChannelOrchestratorPermissionTest {
    private val guildId = GuildId(1L)
    private val ownerId = UserId(999L)
    private val selfId = UserId(42L)
    private val customRoleId = RoleId(50L)

    private fun textChannel(
        id: Long,
        overwrites: List<PermissionOverwrite> = emptyList(),
    ) = GuildTextChannel(
        id = ChannelId(id),
        name = "c$id",
        guildId = guildId,
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
        permissionOverwrites = overwrites,
    )

    private fun newOrch(scope: CoroutineScope, storage: FakeChannelRepository, gw: FakeGatewayEventSource) =
        ChannelOrchestrator(
            sessionScope = scope,
            gatewaySource = gw,
            storage = storage,
            roleStore = RoleStore(),
            selfMemberStore = SelfMemberStore(),
            guildOwnerProvider = { ownerId },
        )

    @Test
    fun `bootstrap permissive — channels visible before role data arrives`() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        storage.persist(textChannel(101L))
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = newOrch(scope, storage, gw)
        testScheduler.runCurrent()

        orch.channelsForGuild(guildId).first().size shouldBe 1
        job.cancel()
    }

    @Test
    fun `after data arrives non-viewable channel is filtered out`() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        val everyoneRole = RoleId(guildId.value)
        // Channel denies VIEW for @everyone.
        val hidden = textChannel(
            201L,
            overwrites = listOf(
                PermissionOverwrite(everyoneRole.value, OverwriteType.Role,
                    allow = 0L, deny = Permissions.VIEW_CHANNEL),
            ),
        )
        val visible = textChannel(202L)
        storage.persist(hidden)
        storage.persist(visible)
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = newOrch(scope, storage, gw)
        testScheduler.runCurrent()

        // Deliver roles (@everyone with VIEW) + self member.
        gw.emit(GatewayDomainEvent.GuildRolesSnapshot(
            guildId,
            listOf(Role(everyoneRole, guildId, "@everyone", Permissions.VIEW_CHANNEL, 0)),
        ))
        gw.emit(GatewayDomainEvent.SelfMemberUpdated(
            Member(userId = selfId, guildId = guildId, roles = emptyList()),
        ))
        testScheduler.runCurrent()

        val list = orch.channelsForGuild(guildId).first()
        list.map { it.id.value }.toSet() shouldBe setOf(202L)
        job.cancel()
    }

    @Test
    fun `owner sees all channels even when everyone denies VIEW`() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        val everyoneRole = RoleId(guildId.value)
        val hidden = textChannel(
            301L,
            overwrites = listOf(
                PermissionOverwrite(everyoneRole.value, OverwriteType.Role,
                    allow = 0L, deny = Permissions.VIEW_CHANNEL),
            ),
        )
        storage.persist(hidden)
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = newOrch(scope, storage, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.GuildRolesSnapshot(
            guildId,
            listOf(Role(everyoneRole, guildId, "@everyone", 0L, 0)),
        ))
        // Self IS the owner.
        gw.emit(GatewayDomainEvent.SelfMemberUpdated(
            Member(userId = ownerId, guildId = guildId, roles = emptyList()),
        ))
        testScheduler.runCurrent()

        orch.channelsForGuild(guildId).first().size shouldBe 1
        job.cancel()
    }

    @Test
    fun `RoleCreated incremental event updates visibility`() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        val everyoneRole = RoleId(guildId.value)
        // Channel denied for @everyone, allowed for custom role.
        val ch = textChannel(
            401L,
            overwrites = listOf(
                PermissionOverwrite(everyoneRole.value, OverwriteType.Role,
                    allow = 0L, deny = Permissions.VIEW_CHANNEL),
                PermissionOverwrite(customRoleId.value, OverwriteType.Role,
                    allow = Permissions.VIEW_CHANNEL, deny = 0L),
            ),
        )
        storage.persist(ch)
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = newOrch(scope, storage, gw)
        testScheduler.runCurrent()

        // Initial: member has no custom role -> only @everyone (deny) applies -> hidden.
        gw.emit(GatewayDomainEvent.GuildRolesSnapshot(
            guildId,
            listOf(Role(everyoneRole, guildId, "@everyone", Permissions.VIEW_CHANNEL, 0)),
        ))
        gw.emit(GatewayDomainEvent.SelfMemberUpdated(
            Member(userId = selfId, guildId = guildId, roles = emptyList()),
        ))
        testScheduler.runCurrent()
        orch.channelsForGuild(guildId).first().isEmpty() shouldBe true

        // Self gets the custom role (e.g. via GUILD_MEMBER_UPDATE) -> visible.
        gw.emit(GatewayDomainEvent.RoleCreated(
            Role(customRoleId, guildId, "Custom", 0L, 0),
        ))
        gw.emit(GatewayDomainEvent.SelfMemberUpdated(
            Member(userId = selfId, guildId = guildId, roles = listOf(customRoleId)),
        ))
        testScheduler.runCurrent()

        orch.channelsForGuild(guildId).first().size shouldBe 1
        job.cancel()
    }

    @Test
    fun `isChannelVisible reflects current state`() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = FakeChannelRepository()
        val everyoneRole = RoleId(guildId.value)
        val hidden = textChannel(
            501L,
            overwrites = listOf(
                PermissionOverwrite(everyoneRole.value, OverwriteType.Role,
                    allow = 0L, deny = Permissions.VIEW_CHANNEL),
            ),
        )
        val visible = textChannel(502L)
        storage.persist(hidden)
        storage.persist(visible)
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = newOrch(scope, storage, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.GuildRolesSnapshot(
            guildId,
            listOf(Role(everyoneRole, guildId, "@everyone", Permissions.VIEW_CHANNEL, 0)),
        ))
        gw.emit(GatewayDomainEvent.SelfMemberUpdated(
            Member(userId = selfId, guildId = guildId, roles = emptyList()),
        ))
        // Trigger collection so visibility index gets populated.
        orch.channelsForGuild(guildId).first()
        testScheduler.runCurrent()

        orch.isChannelVisible(ChannelId(501L)) shouldBe false
        orch.isChannelVisible(ChannelId(502L)) shouldBe true
        // Unknown channel
        orch.isChannelVisible(ChannelId(9999L)) shouldBe null
        job.cancel()
    }
}
