package dev.puklic.repositories

import dev.puklic.domain.Role
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ephemeral, in-memory store of guild roles per architect-report
 * 2026-05-24-channel-permission-design.md §4. Rebuilt from READY; not persisted to SQLite.
 *
 * Memory budget: ~100 guilds × ~50 roles × ~200 B ≈ 1 MB worst-case.
 */
public class RoleStore {
    private val rolesByGuild = MutableStateFlow<Map<GuildId, Map<RoleId, Role>>>(emptyMap())

    public val state: StateFlow<Map<GuildId, Map<RoleId, Role>>> get() = rolesByGuild

    /** Replace the role set for [guildId] with [roles]. Use this from GUILD_CREATE. */
    public fun upsertGuild(guildId: GuildId, roles: List<Role>) {
        rolesByGuild.value = rolesByGuild.value + (guildId to roles.associateBy { it.id })
    }

    /** Insert or update a single role (GUILD_ROLE_CREATE / GUILD_ROLE_UPDATE). */
    public fun upsert(role: Role) {
        val current = rolesByGuild.value
        val guildRoles = current[role.guildId].orEmpty()
        rolesByGuild.value = current + (role.guildId to (guildRoles + (role.id to role)))
    }

    /** Remove a role (GUILD_ROLE_DELETE). No-op if absent. */
    public fun remove(guildId: GuildId, roleId: RoleId) {
        val current = rolesByGuild.value
        val guildRoles = current[guildId] ?: return
        rolesByGuild.value = current + (guildId to (guildRoles - roleId))
    }

    /** Wholesale replace — used on READY and Invalid Session re-IDENTIFY. */
    public fun replaceAll(snapshot: Map<GuildId, Map<RoleId, Role>>) {
        rolesByGuild.value = snapshot
    }

    /** Drop everything for [guildId] (GUILD_DELETE). */
    public fun removeGuild(guildId: GuildId) {
        rolesByGuild.value = rolesByGuild.value - guildId
    }

    /** Convenience non-suspending lookup of the current role map for [guildId]. */
    public fun rolesFor(guildId: GuildId): Map<RoleId, Role> =
        rolesByGuild.value[guildId].orEmpty()
}
