package dev.puklic.repositories

import dev.puklic.domain.Member
import dev.puklic.ids.GuildId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ephemeral store of the **self** [Member] per guild. The "Self" prefix advertises intent —
 * other members are never stored here (see architect-report 2026-05-24 §4, F9 rename).
 */
public class SelfMemberStore {
    private val selfByGuild = MutableStateFlow<Map<GuildId, Member>>(emptyMap())

    public val state: StateFlow<Map<GuildId, Member>> get() = selfByGuild

    public fun upsertSelf(member: Member) {
        selfByGuild.value = selfByGuild.value + (member.guildId to member)
    }

    public fun replaceAll(snapshot: Map<GuildId, Member>) {
        selfByGuild.value = snapshot
    }

    public fun removeGuild(guildId: GuildId) {
        selfByGuild.value = selfByGuild.value - guildId
    }

    public fun selfFor(guildId: GuildId): Member? = selfByGuild.value[guildId]
}
