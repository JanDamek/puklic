package dev.puklic.repositories

import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The DmListOrchestrator must IGNORE non-DM ChannelCreated events (guild text/category).
 * Live trace showed the orchestrator processing every guild channel from READY which both
 * spammed the log and risked subtle bugs if upsert ever touched a non-DM branch.
 */
class DmListOrchestratorFilterTest {

    private fun guildTextChannel(id: Long, guildId: Long): GuildTextChannel = GuildTextChannel(
        id = ChannelId(id),
        name = "c$id",
        guildId = GuildId(guildId),
        parentId = null,
        topic = null,
        position = 0,
        rateLimitPerUser = 0,
        nsfw = false,
    )

    private fun dmChannel(id: Long, recipientId: Long): DmChannel = DmChannel(
        id = ChannelId(id),
        recipients = listOf(
            UserSummary(
                id = UserId(recipientId),
                username = "u$recipientId",
                globalName = null,
                discriminator = null,
                avatarHash = null,
                bot = false,
                system = false,
            ),
        ),
    )

    @Test
    fun guild_channel_events_are_ignored_only_dm_channels_appear() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(guildTextChannel(900L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dmChannel(701L, recipientId = 42L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(guildTextChannel(901L, guildId = 5L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dmChannel(702L, recipientId = 43L)))
        testScheduler.runCurrent()

        val dms = orch.dms.value
        dms.size shouldBe 2
        dms.map { it.id.value }.toSet() shouldBe setOf(701L, 702L)
        job.cancel()
    }
}
