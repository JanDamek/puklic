package dev.puklic.repositories

import dev.puklic.domain.Role
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RoleStoreTest {
    private val g1 = GuildId(1L)
    private val g2 = GuildId(2L)

    private fun role(id: Long, guild: GuildId = g1) = Role(
        id = RoleId(id),
        guildId = guild,
        name = "r$id",
        permissions = 0L,
        position = 0,
    )

    @Test
    fun `upsertGuild replaces role set for guild`() {
        val store = RoleStore()
        store.upsertGuild(g1, listOf(role(10), role(11)))
        store.rolesFor(g1).size shouldBe 2
        store.upsertGuild(g1, listOf(role(12)))
        store.rolesFor(g1).keys shouldBe setOf(RoleId(12L))
    }

    @Test
    fun `upsert adds and updates single role`() {
        val store = RoleStore()
        store.upsert(role(10))
        store.upsert(role(11))
        store.rolesFor(g1).size shouldBe 2
        // update existing
        store.upsert(role(10).copy(name = "renamed"))
        store.rolesFor(g1)[RoleId(10L)]?.name shouldBe "renamed"
    }

    @Test
    fun `remove drops single role`() {
        val store = RoleStore()
        store.upsertGuild(g1, listOf(role(10), role(11)))
        store.remove(g1, RoleId(10L))
        store.rolesFor(g1).keys shouldBe setOf(RoleId(11L))
    }

    @Test
    fun `replaceAll wipes and rebuilds`() {
        val store = RoleStore()
        store.upsertGuild(g1, listOf(role(10)))
        store.replaceAll(mapOf(g2 to mapOf(RoleId(20L) to role(20, g2))))
        store.rolesFor(g1).isEmpty() shouldBe true
        store.rolesFor(g2).size shouldBe 1
    }

    @Test
    fun `per-guild scoping is preserved`() {
        val store = RoleStore()
        store.upsert(role(10, g1))
        store.upsert(role(20, g2))
        store.rolesFor(g1).keys shouldBe setOf(RoleId(10L))
        store.rolesFor(g2).keys shouldBe setOf(RoleId(20L))
    }
}
