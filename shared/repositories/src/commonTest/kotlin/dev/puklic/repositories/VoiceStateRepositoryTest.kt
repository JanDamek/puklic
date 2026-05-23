package dev.puklic.repositories

import dev.puklic.domain.VoiceMember
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class VoiceStateRepositoryTest {

    private val guild = GuildId(10L)
    private val chanA = ChannelId(100L)
    private val chanB = ChannelId(200L)
    private val alice = UserId(1L)
    private val bob = UserId(2L)

    private fun update(
        userId: UserId,
        channelId: ChannelId?,
        guildId: GuildId? = if (channelId == null) null else guild,
        selfMute: Boolean = false,
        selfDeaf: Boolean = false,
        mute: Boolean = false,
        deaf: Boolean = false,
    ) = GatewayDomainEvent.VoiceStateUpdated(
        guildId = guildId,
        channelId = channelId,
        userId = userId,
        sessionId = "s",
        selfMute = selfMute,
        selfDeaf = selfDeaf,
        mute = mute,
        deaf = deaf,
    )

    @Test
    fun user_join_channel_appears_in_membersIn() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA))
        testScheduler.runCurrent()
        repo.membersIn(chanA).map { it.userId } shouldBe listOf(alice)
        job.cancel()
    }

    @Test
    fun user_moves_between_channels() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA))
        testScheduler.runCurrent()
        gw.emit(update(alice, chanB))
        testScheduler.runCurrent()
        repo.membersIn(chanA) shouldBe emptyList()
        repo.membersIn(chanB).map { it.userId } shouldBe listOf(alice)
        // Channel-A entry pruned (no empty entries kept).
        repo.voiceStates.value.containsKey(chanA) shouldBe false
        job.cancel()
    }

    @Test
    fun user_leaves_when_channelId_null() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA))
        testScheduler.runCurrent()
        gw.emit(update(alice, channelId = null, guildId = null))
        testScheduler.runCurrent()
        repo.membersIn(chanA) shouldBe emptyList()
        repo.voiceStates.value shouldBe emptyMap()
        job.cancel()
    }

    @Test
    fun two_users_same_channel_listed_together() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA))
        gw.emit(update(bob, chanA))
        testScheduler.runCurrent()
        repo.membersIn(chanA).map { it.userId } shouldContainExactlyInAnyOrder listOf(alice, bob)
        job.cancel()
    }

    @Test
    fun mute_and_deaf_flags_reflected() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA, selfMute = true, selfDeaf = true, mute = true, deaf = true))
        testScheduler.runCurrent()
        val m = repo.membersIn(chanA).single()
        m.selfMute shouldBe true
        m.selfDeaf shouldBe true
        m.muted shouldBe true
        m.deafened shouldBe true
        job.cancel()
    }

    @Test
    fun bootstrap_seeds_initial_members() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        val initial = listOf(
            VoiceMember(alice, chanA, guild, false, false, false, false),
            VoiceMember(bob, chanA, guild, true, false, false, false),
        )
        gw.emit(GatewayDomainEvent.VoiceStatesBootstrap(guild, initial))
        testScheduler.runCurrent()
        repo.membersIn(chanA).map { it.userId } shouldContainExactlyInAnyOrder listOf(alice, bob)
        job.cancel()
    }

    @Test
    fun bootstrap_supersedes_prior_state_for_same_guild() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val repo = VoiceStateRepository(scope, gw)
        testScheduler.runCurrent()
        gw.emit(update(alice, chanA))
        testScheduler.runCurrent()
        val initial = listOf(VoiceMember(bob, chanB, guild, false, false, false, false))
        gw.emit(GatewayDomainEvent.VoiceStatesBootstrap(guild, initial))
        testScheduler.runCurrent()
        repo.membersIn(chanA) shouldBe emptyList()
        repo.membersIn(chanB).map { it.userId } shouldBe listOf(bob)
        job.cancel()
    }
}
