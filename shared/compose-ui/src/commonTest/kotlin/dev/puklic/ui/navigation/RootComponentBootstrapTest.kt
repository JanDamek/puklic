package dev.puklic.ui.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
import dev.puklic.platform.SecureStorage
import dev.puklic.session.DiscordSession
import dev.puklic.session.GatewayLifecycleEvent
import dev.puklic.session.SessionManager
import dev.puklic.session.SessionTransport
import dev.puklic.session.TokenValidation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Exercises [RootComponent]'s three-state routing: Bootstrapping → Login/Main based on the
 * outcome of [SessionManager.loadStoredSession].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootComponentBootstrapTest {

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { kotlinx.coroutines.Dispatchers.setMain(mainDispatcher) }

    @AfterTest fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    @Test
    fun initial_state_is_login_when_bootstrap_not_started() = runTest {
        val (root, _) = newRoot(this)
        root.routerState.value shouldBe RouterState.Login
    }

    @Test
    fun no_stored_token_routes_to_login() = runTest {
        val (root, mgr) = newRoot(this)
        mgr.loadStoredSession()
        advanceUntilIdle()
        root.routerState.value shouldBe RouterState.Login
    }

    @Test
    fun valid_stored_token_routes_to_main() = runTest {
        val (root, mgr) = newRoot(this, storedToken = "valid")
        mgr.loadStoredSession()
        advanceUntilIdle()
        root.routerState.value shouldBe RouterState.Main
    }

    @Test
    fun invalid_stored_token_falls_back_to_login() = runTest {
        val (root, mgr) = newRoot(
            this,
            storedToken = "stale",
            validation = TokenValidation.Unauthorized,
        )
        mgr.loadStoredSession()
        advanceUntilIdle()
        root.routerState.value shouldBe RouterState.Login
    }

    private fun newRoot(
        scope: CoroutineScope,
        storedToken: String? = null,
        validation: TokenValidation = TokenValidation.Ok(fakeSelf()),
    ): Pair<RootComponent, SessionManager> {
        val parent = Job()
        val storage = FakeSecureStorage()
        if (storedToken != null) storage.preset(SessionManager.TOKEN_KEY, storedToken)
        val transport = FakeTransport(validation)
        val mgr = SessionManager(
            CoroutineScope(scope.coroutineContext + parent),
            storage,
            sessionFactory = { t ->
                DiscordSession(CoroutineScope(scope.coroutineContext + parent), t, transport)
            },
        )
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return RootComponent(ctx, mgr) to mgr
    }
}

private class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
    fun preset(key: String, value: String) { map[key] = value }
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun list(): List<String> = map.keys.toList()
}

private class FakeTransport(private val validation: TokenValidation) : SessionTransport {
    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(replay = 0, extraBufferCapacity = 8)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()
    override suspend fun validateToken(token: String): TokenValidation = validation
    override suspend fun connectGateway(token: String) {}
    override suspend fun disconnectGateway() {}
}

private fun fakeSelf(): UserSummary = UserSummary(
    id = UserId(1L),
    username = "self",
    globalName = "Self",
    discriminator = null,
    avatarHash = null,
    bot = false,
    system = false,
)
