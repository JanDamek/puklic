package dev.puklic.repositories

import dev.puklic.domain.ChannelType
import dev.puklic.domain.DmChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * DmListOrchestrator must sort DMs by lastMessageId descending (Discord snowflakes are
 * time-monotonic). Null lastMessageId sinks to the bottom. CHANNEL_UPDATE with a null
 * last_message_id MUST NOT overwrite a previously-known value. MESSAGE_CREATE bumps the
 * affected DM to the top.
 */
class DmListOrchestratorSortTest {

    private fun dm(id: Long, recipientId: Long, lastMessageId: Long? = null): DmChannel = DmChannel(
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
        lastMessageId = lastMessageId?.let(::MessageId),
    )

    private fun groupDm(id: Long, lastMessageId: Long? = null): DmChannel = DmChannel(
        id = ChannelId(id),
        recipients = listOf(
            UserSummary(UserId(1L), "u1", null, null, null, bot = false, system = false),
            UserSummary(UserId(2L), "u2", null, null, null, bot = false, system = false),
        ),
        lastMessageId = lastMessageId?.let(::MessageId),
    ).also { check(it.type == ChannelType.DM || it.type == ChannelType.DM) }

    @Test
    fun empty_state_is_empty_list() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        orch.dms.value shouldBe emptyList()
        job.cancel()
    }

    @Test
    fun single_dm_with_last_message_id_is_first() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(100L, 1L, lastMessageId = 100L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(100L)
        job.cancel()
    }

    @Test
    fun two_dms_sorted_descending_by_last_message_id() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(702L, 2L, lastMessageId = 200L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(702L, 701L)
        job.cancel()
    }

    @Test
    fun upsert_same_dm_with_larger_id_moves_it_to_top() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(702L, 2L, lastMessageId = 200L)))
        gw.emit(GatewayDomainEvent.ChannelUpdated(dm(701L, 1L, lastMessageId = 999L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(701L, 702L)
        job.cancel()
    }

    @Test
    fun message_created_for_known_dm_bumps_to_top() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(702L, 2L, lastMessageId = 200L)))
        gw.emit(GatewayDomainEvent.MessageCreated(message(id = 5000L, channelId = ChannelId(701L), ts = 1L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(701L, 702L)
        orch.dms.value.first().lastMessageId?.value shouldBe 5000L
        job.cancel()
    }

    @Test
    fun null_last_message_id_dm_goes_to_bottom() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(702L, 2L, lastMessageId = null)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(703L, 3L, lastMessageId = 200L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(703L, 701L, 702L)
        job.cancel()
    }

    @Test
    fun message_created_for_unknown_channel_is_noop() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.MessageCreated(message(id = 5000L, channelId = ChannelId(9999L), ts = 1L)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(701L)
        orch.dms.value.first().lastMessageId?.value shouldBe 50L
        job.cancel()
    }

    @Test
    fun channel_update_without_last_message_id_preserves_previous() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelUpdated(dm(701L, 1L, lastMessageId = null)))
        testScheduler.runCurrent()

        orch.dms.value.first().lastMessageId?.value shouldBe 50L
        job.cancel()
    }

    @Test
    fun two_null_last_message_id_dms_preserve_insertion_order() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = null)))
        gw.emit(GatewayDomainEvent.ChannelCreated(dm(702L, 2L, lastMessageId = null)))
        testScheduler.runCurrent()

        orch.dms.value.map { it.id.value } shouldBe listOf(701L, 702L)
        job.cancel()
    }

    @Test
    fun channel_updated_with_non_null_overwrites_older() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(dm(701L, 1L, lastMessageId = 50L)))
        gw.emit(GatewayDomainEvent.ChannelUpdated(dm(701L, 1L, lastMessageId = 9000L)))
        testScheduler.runCurrent()

        orch.dms.value.first().lastMessageId?.value shouldBe 9000L
        job.cancel()
    }

    @Test
    fun group_dm_uses_same_upsert_path() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = DmListOrchestrator(scope, gw)
        testScheduler.runCurrent()

        gw.emit(GatewayDomainEvent.ChannelCreated(groupDm(800L, lastMessageId = 100L)))
        gw.emit(GatewayDomainEvent.ChannelCreated(groupDm(800L, lastMessageId = 500L)))
        testScheduler.runCurrent()

        orch.dms.value.size shouldBe 1
        orch.dms.value.first().lastMessageId?.value shouldBe 500L
        job.cancel()
    }
}
