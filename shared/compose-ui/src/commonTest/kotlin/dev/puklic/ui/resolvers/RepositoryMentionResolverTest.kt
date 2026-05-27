package dev.puklic.ui.resolvers

import dev.puklic.domain.Channel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.Role
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.persistence.repository.UserRepository
import dev.puklic.repositories.RoleStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RepositoryMentionResolverTest {
    private val guildId = GuildId(1L)

    private fun user(id: Long, name: String, globalName: String? = null) = UserSummary(
        id = UserId(id),
        username = name,
        globalName = globalName,
        discriminator = null,
        avatarHash = null,
        bot = false,
        system = false,
    )

    private fun textChannel(id: Long, name: String) = GuildTextChannel(
        id = ChannelId(id),
        name = name,
        guildId = guildId,
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    private fun role(id: Long, name: String, color: Int = 0) = Role(
        id = RoleId(id),
        guildId = guildId,
        name = name,
        permissions = 0L,
        position = 0,
        color = color,
    )

    private class StubUserRepo(private val users: Map<Long, UserSummary>) : UserRepository {
        override suspend fun findById(id: UserId): UserSummary? = users[id.value]
        override suspend fun findByIds(ids: Collection<UserId>): List<UserSummary> =
            ids.mapNotNull { users[it.value] }
        override suspend fun searchByName(query: String, limit: Int): List<UserSummary> = emptyList()
        override suspend fun persist(user: UserSummary) = Unit
        override suspend fun persistAll(users: List<UserSummary>) = Unit
        override suspend fun delete(id: UserId) = Unit
    }

    private class StubChannelRepo(private val channels: Map<Long, Channel>) : ChannelRepository {
        override fun observeByGuild(guildId: GuildId): Flow<List<Channel>> =
            MutableStateFlow(channels.values.toList())
        override suspend fun findById(id: ChannelId): Channel? = channels[id.value]
        override suspend fun persist(channel: Channel) = Unit
        override suspend fun persistAll(channels: List<Channel>) = Unit
        override suspend fun updateLastMessage(id: ChannelId, lastMessageId: MessageId) = Unit
        override suspend fun delete(id: ChannelId) = Unit
    }

    private fun resolver(
        users: Map<Long, UserSummary> = emptyMap(),
        channels: Map<Long, Channel> = emptyMap(),
        roleStore: RoleStore = RoleStore(),
    ) = RepositoryMentionResolver(
        userRepository = StubUserRepo(users),
        channelRepository = StubChannelRepo(channels),
        roleStore = roleStore,
    )

    @Test
    fun `resolveUser returns cached summary when present`() = runTest {
        val r = resolver(users = mapOf(7L to user(7L, "ada", globalName = "Ada Lovelace")))
        val result = r.resolveUser(UserId(7L)).first()
        result?.username shouldBe "ada"
        result?.globalName shouldBe "Ada Lovelace"
    }

    @Test
    fun `resolveUser returns null on cache miss`() = runTest {
        val r = resolver()
        r.resolveUser(UserId(99L)).first().shouldBeNull()
    }

    @Test
    fun `resolveChannel returns cached channel when present`() = runTest {
        val r = resolver(channels = mapOf(11L to textChannel(11L, "general")))
        val result = r.resolveChannel(ChannelId(11L)).first()
        result?.name shouldBe "general"
    }

    @Test
    fun `resolveChannel returns null on cache miss`() = runTest {
        val r = resolver()
        r.resolveChannel(ChannelId(123L)).first().shouldBeNull()
    }

    @Test
    fun `resolveRole returns name from RoleStore with null color when role color is zero`() = runTest {
        val store = RoleStore().apply {
            upsertGuild(guildId, listOf(role(50L, "admins", color = 0)))
        }
        val r = resolver(roleStore = store)
        val result = r.resolveRole(RoleId(50L)).first()
        result?.name shouldBe "admins"
        result?.colorArgb.shouldBeNull()
    }

    @Test
    fun `resolveRole returns ARGB color when role has non-zero color`() = runTest {
        val store = RoleStore().apply {
            upsertGuild(guildId, listOf(role(51L, "ops", color = 0xCC0000)))
        }
        val r = resolver(roleStore = store)
        val result = r.resolveRole(RoleId(51L)).first()
        result?.name shouldBe "ops"
        result?.colorArgb shouldBe 0xFFCC0000.toInt()
    }

    @Test
    fun `resolveRole returns null on cache miss`() = runTest {
        val r = resolver()
        r.resolveRole(RoleId(404L)).first().shouldBeNull()
    }

    @Test
    fun `resolveRole searches across multiple guilds`() = runTest {
        val otherGuild = GuildId(2L)
        val store = RoleStore().apply {
            upsertGuild(guildId, listOf(role(50L, "admins")))
            upsertGuild(
                otherGuild,
                listOf(
                    Role(RoleId(60L), otherGuild, "mods", permissions = 0L, position = 0),
                ),
            )
        }
        val r = resolver(roleStore = store)
        r.resolveRole(RoleId(60L)).first()?.name shouldBe "mods"
    }
}
