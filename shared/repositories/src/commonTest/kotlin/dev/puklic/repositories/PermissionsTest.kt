package dev.puklic.repositories

import dev.puklic.domain.GuildChannel
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
import kotlin.test.Test

class PermissionsTest {
    private val guildId = GuildId(100L)
    private val ownerId = UserId(1L)
    private val selfId = UserId(2L)
    private val everyoneRoleId = Permissions.everyoneRoleId(guildId)
    private val customRoleId = RoleId(50L)

    private fun channel(
        id: Long = 10L,
        overwrites: List<PermissionOverwrite> = emptyList(),
    ): GuildChannel = GuildTextChannel(
        id = ChannelId(id),
        name = "general",
        guildId = guildId,
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
        permissionOverwrites = overwrites,
    )

    private fun member(roles: List<RoleId> = emptyList()): Member =
        Member(userId = selfId, guildId = guildId, roles = roles)

    private fun role(id: RoleId, permissions: Long): Role =
        Role(id = id, guildId = guildId, name = "r${id.value}", permissions = permissions, position = 0)

    @Test
    fun `everyone VIEW grants visibility to unroled member`() {
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL))
        Permissions.canView(member(), channel(), roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `everyone without VIEW denies visibility`() {
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, 0L))
        Permissions.canView(member(), channel(), roles, ownerId, everyoneRoleId) shouldBe false
    }

    @Test
    fun `owner sees channel regardless of permissions`() {
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, 0L))
        val ownerMember = Member(userId = ownerId, guildId = guildId, roles = emptyList())
        Permissions.canView(ownerMember, channel(), roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `ADMINISTRATOR bypasses channel overwrites`() {
        val roles = mapOf(
            everyoneRoleId to role(everyoneRoleId, Permissions.ADMINISTRATOR),
        )
        val ch = channel(overwrites = listOf(
            PermissionOverwrite(everyoneRoleId.value, OverwriteType.Role, allow = 0L, deny = Permissions.VIEW_CHANNEL),
        ))
        Permissions.canView(member(), ch, roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `role allow overrides everyone deny`() {
        val roles = mapOf(
            everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL),
            customRoleId to role(customRoleId, 0L),
        )
        val ch = channel(overwrites = listOf(
            PermissionOverwrite(everyoneRoleId.value, OverwriteType.Role, allow = 0L, deny = Permissions.VIEW_CHANNEL),
            PermissionOverwrite(customRoleId.value, OverwriteType.Role, allow = Permissions.VIEW_CHANNEL, deny = 0L),
        ))
        Permissions.canView(member(listOf(customRoleId)), ch, roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `member overwrite overrides role overwrite`() {
        val roles = mapOf(
            everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL),
            customRoleId to role(customRoleId, 0L),
        )
        // Role overwrite allows, but member-level overwrite denies — final = denied.
        val ch = channel(overwrites = listOf(
            PermissionOverwrite(customRoleId.value, OverwriteType.Role, allow = Permissions.VIEW_CHANNEL, deny = 0L),
            PermissionOverwrite(selfId.value, OverwriteType.Member, allow = 0L, deny = Permissions.VIEW_CHANNEL),
        ))
        Permissions.canView(member(listOf(customRoleId)), ch, roles, ownerId, everyoneRoleId) shouldBe false
    }

    @Test
    fun `deny applied before allow on same overwrite`() {
        // Spec: permissions = (permissions AND NOT deny) OR allow — so allow wins on the same overwrite.
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, 0L))
        val ch = channel(overwrites = listOf(
            PermissionOverwrite(everyoneRoleId.value, OverwriteType.Role,
                allow = Permissions.VIEW_CHANNEL, deny = Permissions.VIEW_CHANNEL),
        ))
        Permissions.canView(member(), ch, roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `no overwrites with everyone VIEW remains visible`() {
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL))
        Permissions.canView(member(), channel(), roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `multiple role overwrites OR deny and OR allow`() {
        val r1 = RoleId(60L)
        val r2 = RoleId(61L)
        val roles = mapOf(
            everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL),
            r1 to role(r1, 0L),
            r2 to role(r2, 0L),
        )
        // r1 denies VIEW, r2 allows VIEW -> deny OR'd first, allow OR'd -> allow wins.
        val ch = channel(overwrites = listOf(
            PermissionOverwrite(r1.value, OverwriteType.Role, allow = 0L, deny = Permissions.VIEW_CHANNEL),
            PermissionOverwrite(r2.value, OverwriteType.Role, allow = Permissions.VIEW_CHANNEL, deny = 0L),
        ))
        Permissions.canView(member(listOf(r1, r2)), ch, roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `everyone role id equals guild id convention`() {
        Permissions.everyoneRoleId(GuildId(12345L)) shouldBe RoleId(12345L)
    }

    @Test
    fun `unknown member role is skipped gracefully`() {
        // Member.roles references a role that's not in rolesById — must not crash.
        val roles = mapOf(everyoneRoleId to role(everyoneRoleId, Permissions.VIEW_CHANNEL))
        val staleRole = RoleId(999L)
        Permissions.canView(member(listOf(staleRole)), channel(), roles, ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `canViewSafe permissive when member null`() {
        Permissions.canViewSafe(null, channel(), emptyMap(), ownerId, everyoneRoleId) shouldBe true
    }

    @Test
    fun `canViewSafe permissive when roles empty`() {
        Permissions.canViewSafe(member(), channel(), emptyMap(), ownerId, everyoneRoleId) shouldBe true
    }
}
