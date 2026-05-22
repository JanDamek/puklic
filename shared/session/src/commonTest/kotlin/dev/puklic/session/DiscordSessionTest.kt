package dev.puklic.session

import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DiscordSessionTest {

    private fun newSession(parentJob: Job, transport: SessionTransport, token: String = "good"): DiscordSession {
        // attach a child scope so we can cancel just this test's coroutines
        return DiscordSession(CoroutineScope(parentJob), token, transport)
    }

    @Test
    fun connect_with_invalid_token_emits_TokenInvalid() = runTest {
        val parent = Job()
        val transport = FakeSessionTransport(validation = TokenValidation.Unauthorized)
        val session = DiscordSession(CoroutineScope(coroutineContext + parent), "bad", transport)
        session.connect()
        session.state.value shouldBe SessionState.TokenInvalid
        parent.cancel()
    }

    @Test
    fun connect_with_transport_error_emits_Failed() = runTest {
        val parent = Job()
        val transport = FakeSessionTransport(validation = TokenValidation.TransportError("offline"))
        val session = DiscordSession(CoroutineScope(coroutineContext + parent), "x", transport)
        session.connect()
        (session.state.value is SessionState.Failed) shouldBe true
        parent.cancel()
    }

    @Test
    fun connect_then_ready_emits_Connected() = runTest {
        val self = UserSummary(UserId(7L), "me", null, null, null, bot = false, system = false)
        val parent = Job()
        val transport = FakeSessionTransport(validation = TokenValidation.Ok(self))
        val session = DiscordSession(CoroutineScope(coroutineContext + parent), "good", transport)
        session.connect()
        testScheduler.runCurrent()
        transport.emit(GatewayLifecycleEvent.Connected("sess-1"))
        testScheduler.runCurrent()

        val s = session.state.value
        (s is SessionState.Connected) shouldBe true
        (s as SessionState.Connected).sessionId shouldBe "sess-1"
        s.selfUser shouldBe self
        session.selfUser.value shouldBe self
        transport.connectCalls shouldBe 1
        parent.cancel()
    }

    @Test
    fun reconnecting_event_maps_to_state() = runTest {
        val parent = Job()
        val transport = FakeSessionTransport()
        val session = DiscordSession(CoroutineScope(coroutineContext + parent), "good", transport)
        session.connect()
        testScheduler.runCurrent()
        transport.emit(GatewayLifecycleEvent.Reconnecting(secondsUntilRetry = 5))
        testScheduler.runCurrent()
        session.state.value shouldBe SessionState.Reconnecting(5)
        parent.cancel()
    }

    @Test
    fun disconnect_stops_transport_and_sets_Disconnected() = runTest {
        val parent = Job()
        val transport = FakeSessionTransport()
        val session = DiscordSession(CoroutineScope(coroutineContext + parent), "good", transport)
        session.connect()
        testScheduler.runCurrent()
        session.disconnect()
        transport.disconnectCalls shouldBe 1
        session.state.value shouldBe SessionState.Disconnected
        parent.cancel()
    }
}
