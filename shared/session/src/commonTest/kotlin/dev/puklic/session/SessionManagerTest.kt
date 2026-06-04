package dev.puklic.session

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SessionManagerTest {

    @Test
    fun startSessionWithToken_persists_token_and_sets_active() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        val result = mgr.startSessionWithToken("token-1")
        result.isSuccess shouldBe true
        storage.get(SessionManager.TOKEN_KEY) shouldBe "token-1"
        (mgr.activeSession.value != null) shouldBe true
        parent.cancel()
    }

    @Test
    fun startSessionWithToken_401_returns_failure_and_does_not_persist() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport(validation = TokenValidation.Unauthorized())
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        val result = mgr.startSessionWithToken("bad")
        result.isFailure shouldBe true
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        mgr.activeSession.value shouldBe null
        parent.cancel()
    }

    @Test
    fun loadStoredSession_returns_true_when_token_present() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        storage.put(SessionManager.TOKEN_KEY, "stored")
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        val loaded = mgr.loadStoredSession()
        loaded shouldBe true
        (mgr.activeSession.value != null) shouldBe true
        parent.cancel()
    }

    @Test
    fun loadStoredSession_returns_false_when_no_token() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )
        mgr.loadStoredSession() shouldBe false
        parent.cancel()
    }

    @Test
    fun loadStoredSession_removes_invalid_token() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        storage.put(SessionManager.TOKEN_KEY, "stale")
        val transport = FakeSessionTransport(validation = TokenValidation.Unauthorized())
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        mgr.loadStoredSession() shouldBe false
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        parent.cancel()
    }

    @Test
    fun endSession_with_wipeToken_clears_storage() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )
        mgr.startSessionWithToken("token-x")

        mgr.endSession(wipeToken = true)
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        mgr.activeSession.value shouldBe null
        parent.cancel()
    }
}
