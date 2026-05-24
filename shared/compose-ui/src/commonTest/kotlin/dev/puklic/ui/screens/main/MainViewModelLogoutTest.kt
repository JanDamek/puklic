package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.platform.SecureStorage
import dev.puklic.session.SessionManager
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Behavioral tests for the existing `MainViewModel.logout()` API (issue #8 architect §11).
 * Asserts the contract: logout wipes the persisted token via `SessionManager.endSession(wipeToken=true)`.
 *
 * The token-wipe effect is verifiable through the public SecureStorage seen by the test —
 * we don't need to introspect the SessionManager directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLogoutTest {

    @Test
    fun logout_invokes_endSession_with_wipeToken_true() = runTest(UnconfinedTestDispatcher()) {
        val parent = Job()
        val storage = InMemorySecureStorage()
        storage.put(SessionManager.TOKEN_KEY, "seeded-token")
        val sessionManager = SessionManager(
            applicationScope = CoroutineScope(coroutineContext + parent),
            secureStorage = storage,
            sessionFactory = { token ->
                // logout() never reaches sessionFactory in this test (no active session).
                throw IllegalStateException("sessionFactory should not be called in logout test, token=$token")
            },
        )
        val vm = newVm(this, sessionManager)

        vm.logout()

        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        sessionManager.activeSession.value shouldBe null
        parent.cancel()
    }

    @Test
    fun logout_with_null_sessionManager_is_noop() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this, sessionManager = null)
        // Must not throw.
        vm.logout()
    }

    private fun newVm(
        scope: kotlinx.coroutines.test.TestScope,
        sessionManager: SessionManager?,
    ): MainViewModel = MainViewModel(
        componentContext = newComponentContext(),
        orchestrators = null,
        sessionTransport = null,
        externalScope = scope,
        sessionManager = sessionManager,
    )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }

    /** Minimal commonTest-local fake; the production `FakeSecureStorage` in `:shared:session` is internal. */
    private class InMemorySecureStorage : SecureStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun list(): List<String> = map.keys.toList()
    }
}
