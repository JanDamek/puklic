package dev.puklic.repositories

import dev.puklic.domain.Member
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SelfMemberStoreTest {
    private val g1 = GuildId(1L)
    private val g2 = GuildId(2L)
    private val self = UserId(99L)

    private fun member(guild: GuildId, roles: List<Long> = emptyList()): Member =
        Member(userId = self, guildId = guild, roles = roles.map(::RoleId))

    @Test
    fun `upsertSelf stores per-guild self entry`() {
        val store = SelfMemberStore()
        store.upsertSelf(member(g1, listOf(10L)))
        store.upsertSelf(member(g2, listOf(20L)))
        store.selfFor(g1)?.roles shouldBe listOf(RoleId(10L))
        store.selfFor(g2)?.roles shouldBe listOf(RoleId(20L))
    }

    @Test
    fun `upsertSelf overwrites previous entry for same guild`() {
        val store = SelfMemberStore()
        store.upsertSelf(member(g1, listOf(10L)))
        store.upsertSelf(member(g1, listOf(11L, 12L)))
        store.selfFor(g1)?.roles shouldBe listOf(RoleId(11L), RoleId(12L))
    }

    @Test
    fun `replaceAll wipes and rebuilds`() {
        val store = SelfMemberStore()
        store.upsertSelf(member(g1))
        store.replaceAll(mapOf(g2 to member(g2)))
        store.selfFor(g1) shouldBe null
        store.selfFor(g2)?.guildId shouldBe g2
    }

    @Test
    fun `removeGuild drops a single guild`() {
        val store = SelfMemberStore()
        store.upsertSelf(member(g1))
        store.upsertSelf(member(g2))
        store.removeGuild(g1)
        store.selfFor(g1) shouldBe null
        store.selfFor(g2) shouldBe member(g2)
    }
}
