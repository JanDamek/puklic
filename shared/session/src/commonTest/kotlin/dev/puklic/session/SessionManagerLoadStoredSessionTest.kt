package dev.puklic.session

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Behavioral tests for [SessionManager.loadStoredSession] focused on the auto-restore contract
 * consumed by `RootComponent` (and the desktop `DependencyGraph`):
 *  - Stored valid token → activeSession non-null, bootstrap phase ends at Done.
 *  - No stored token → activeSession null, bootstrap phase ends at Done.
 *  - Stored token rejected with 401 → token removed from storage, activeSession null,
 *    bootstrap phase ends at Done.
 */
class SessionManagerLoadStoredSessionTest {

    @Test
    fun storedValidToken_yieldsActiveSession_andDonePhase() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        storage.put(SessionManager.TOKEN_KEY, "valid")
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        mgr.bootstrap.value shouldBe BootstrapPhase.Idle
        mgr.loadStoredSession() shouldBe true
        (mgr.activeSession.value != null) shouldBe true
        mgr.bootstrap.value shouldBe BootstrapPhase.Done
        storage.get(SessionManager.TOKEN_KEY) shouldBe "valid"
        parent.cancel()
    }

    @Test
    fun emptyStorage_yieldsNoSession_andDonePhase() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        mgr.loadStoredSession() shouldBe false
        mgr.activeSession.value shouldBe null
        mgr.bootstrap.value shouldBe BootstrapPhase.Done
        parent.cancel()
    }

    @Test
    fun storedToken_rejected401_isRemovedFromStorage() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        storage.put(SessionManager.TOKEN_KEY, "stale")
        val transport = FakeSessionTransport(validation = TokenValidation.Unauthorized)
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
        )

        mgr.loadStoredSession() shouldBe false
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        mgr.activeSession.value shouldBe null
        mgr.bootstrap.value shouldBe BootstrapPhase.Done
        parent.cancel()
    }
}
