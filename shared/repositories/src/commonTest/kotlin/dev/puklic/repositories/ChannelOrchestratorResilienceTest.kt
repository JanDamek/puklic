package dev.puklic.repositories

import dev.puklic.domain.Channel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ChannelRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Verifies that an exception thrown by [ChannelRepository.persist] on a single event does NOT
 * tear down the orchestrator's collect block — subsequent events must still be processed.
 *
 * Mirrors the live-bug shape: persist worked for the first N events, then a malformed payload
 * caused storage.upsert to throw, and the silent coroutine death made every subsequent
 * ChannelCreated event invisible to SQLite (Unicorn/Junie channels never landed).
 */
class ChannelOrchestratorResilienceTest {

    private class ExplodingChannelRepository(
        private val explodeOnId: Long,
    ) : ChannelRepository {
        private val state = MutableStateFlow<Map<ChannelId, Channel>>(emptyMap())
        val persistedIds: MutableList<Long> = mutableListOf()

        override fun observeByGuild(guildId: GuildId): Flow<List<Channel>> = state.map { snap ->
            snap.values.filter { it is GuildTextChannel && it.guildId == guildId }
                .sortedBy { it.id.value }
        }

        override suspend fun findById(id: ChannelId): Channel? = state.value[id]

        override suspend fun persist(channel: Channel) {
            if (channel.id.value == explodeOnId) {
                error("simulated persist failure for id=$explodeOnId")
            }
            persistedIds += channel.id.value
            state.update { it + (channel.id to channel) }
        }

        override suspend fun persistAll(channels: List<Channel>) {
            channels.forEach { persist(it) }
        }

        override suspend fun updateLastMessage(id: ChannelId, lastMessageId: MessageId) = Unit
        override suspend fun delete(id: ChannelId) { state.update { it - id } }
    }

    private fun textChannel(id: Long, guildId: Long): GuildTextChannel = GuildTextChannel(
        id = ChannelId(id),
        name = "c$id",
        guildId = GuildId(guildId),
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    @Test
    fun persist_exception_on_one_event_does_not_kill_collector() = runTest {
        val gw = FakeGatewayEventSource()
        val storage = ExplodingChannelRepository(explodeOnId = 102L)
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        ChannelOrchestrator(scope, gw, storage)
        testScheduler.runCurrent()

        // 5 events: id=102 will explode, the other four must still be persisted.
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(101L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(102L, guildId = 5L))) // boom
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(103L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(104L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(textChannel(105L, guildId = 5L)))
        testScheduler.runCurrent()

        storage.persistedIds shouldBe listOf(101L, 103L, 104L, 105L)
        job.cancel()
    }
}
