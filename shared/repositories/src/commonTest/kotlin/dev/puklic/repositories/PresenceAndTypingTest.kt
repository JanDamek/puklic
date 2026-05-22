package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PresenceOrchestratorTest {
    @Test
    fun presence_updated_event_is_recorded() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = PresenceOrchestrator(scope, gw)
        testScheduler.runCurrent()
        gw.emit(GatewayDomainEvent.PresenceUpdated(UserId(1L), PresenceState.ONLINE))
        testScheduler.runCurrent()
        orch.presences.value[UserId(1L)] shouldBe PresenceState.ONLINE
        job.cancel()
    }

    @Test
    fun offline_event_removes_user_from_map() = runTest {
        val gw = FakeGatewayEventSource()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = PresenceOrchestrator(scope, gw)
        testScheduler.runCurrent()
        gw.emit(GatewayDomainEvent.PresenceUpdated(UserId(1L), PresenceState.ONLINE))
        testScheduler.runCurrent()
        gw.emit(GatewayDomainEvent.PresenceUpdated(UserId(1L), PresenceState.OFFLINE))
        testScheduler.runCurrent()
        orch.presences.value[UserId(1L)] shouldBe null
        orch.presenceOf(UserId(1L)) shouldBe PresenceState.OFFLINE
        job.cancel()
    }
}

class TypingOrchestratorTest {
    @Test
    fun typing_start_recorded_then_expires_on_tick() = runTest {
        val gw = FakeGatewayEventSource()
        var now = 1000L
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val orch = TypingOrchestrator(scope, gw, nowEpochSeconds = { now })
        testScheduler.runCurrent()
        gw.emit(GatewayDomainEvent.TypingStarted(ChannelId(3L), UserId(7L), timestampEpochSeconds = 1000L))
        testScheduler.runCurrent()
        orch.typing.value[ChannelId(3L)] shouldBe setOf(UserId(7L))

        now = 2000L
        orch.tickExpiry()
        testScheduler.runCurrent()
        orch.typing.value[ChannelId(3L)] shouldBe null
        job.cancel()
    }
}
