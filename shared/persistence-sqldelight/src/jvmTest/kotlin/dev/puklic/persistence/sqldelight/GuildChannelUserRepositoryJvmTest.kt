package dev.puklic.persistence.sqldelight

import dev.puklic.domain.Guild
import dev.puklic.domain.GuildFeature
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class GuildChannelUserRepositoryJvmTest {

    private val now: () -> Long = { 0L }

    @Test
    fun guild_repository_persists_and_observes() = runTest {
        val db = newInMemoryDatabase()
        val repo = GuildRepositoryImpl(db, Dispatchers.Unconfined, now)
        val g = Guild(
            id = GuildId(1L),
            name = "Alpha",
            iconHash = "abc",
            ownerId = UserId(2L),
            features = setOf(GuildFeature.COMMUNITY, GuildFeature.VERIFIED),
            memberCount = 42,
        )
        repo.persist(g)

        val loaded = repo.findById(GuildId(1L))
        loaded?.name shouldBe "Alpha"
        loaded?.features shouldContainExactlyInAnyOrder setOf(GuildFeature.COMMUNITY, GuildFeature.VERIFIED)

        repo.observeAll().first().size shouldBe 1
    }

    @Test
    fun channel_repository_persists_guild_text_channel_with_fk_to_guild() = runTest {
        val db = newInMemoryDatabase()
        val guildRepo = GuildRepositoryImpl(db, Dispatchers.Unconfined, now)
        val channelRepo = ChannelRepositoryImpl(db, Dispatchers.Unconfined, now)

        guildRepo.persist(
            Guild(GuildId(1L), "g", null, UserId(2L), emptySet(), null),
        )
        channelRepo.persist(
            GuildTextChannel(
                id = ChannelId(10L),
                name = "general",
                guildId = GuildId(1L),
                parentId = null,
                topic = "chat",
                position = 0,
                rateLimitPerUser = 5,
                nsfw = false,
            ),
        )

        val channels = channelRepo.observeByGuild(GuildId(1L)).first()
        channels.size shouldBe 1
        (channels.first() as GuildTextChannel).rateLimitPerUser shouldBe 5
    }

    @Test
    fun user_repository_persists_batch_and_resolves_by_ids() = runTest {
        val db = newInMemoryDatabase()
        val repo = UserRepositoryImpl(db, Dispatchers.Unconfined, now)
        val batch = (1L..3L).map {
            UserSummary(UserId(it), "user-$it", null, null, null, bot = it == 2L, system = false)
        }
        repo.persistAll(batch)

        val found = repo.findByIds(listOf(UserId(1L), UserId(3L)))
        found.map { it.id.value } shouldContainExactlyInAnyOrder listOf(1L, 3L)
        repo.findById(UserId(2L))!!.bot shouldBe true
    }
}
