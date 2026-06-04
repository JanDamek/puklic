package dev.puklic.ui.screens.login

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @Test
    fun `onTokenChange trims and clears error`() = runTest {
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized())
        vm.selectMode(LoginMode.TOKEN)
        vm.onTokenChange("  abc  ")
        vm.state.value.token shouldBe "abc"
        vm.state.value.canSubmit shouldBe true
    }

    @Test
    fun `submit with invalid token populates error`() = runTest {
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized())
        vm.selectMode(LoginMode.TOKEN)
        vm.onTokenChange("bad")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.submitting shouldBe false
        (vm.state.value.error != null) shouldBe true
    }

    @Test
    fun `submit with empty token is a no-op`() = runTest {
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized())
        vm.selectMode(LoginMode.TOKEN)
        vm.submit()
        advanceUntilIdle()
        vm.state.value.submitting shouldBe false
        vm.state.value.error shouldBe null
    }

    @Test
    fun `default mode is credentials per issue 90`() = runTest {
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized())
        vm.state.value.mode shouldBe LoginMode.CREDENTIALS
    }

    @Test
    fun `save password persists credentials on submit and unchecking wipes them`() = runTest {
        val storage = InMemoryStorage()
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized(), storage = storage)
        vm.onLoginFieldChange("user@example.com")
        vm.onPasswordChange("hunter2")
        vm.onSavePasswordChange(true)
        vm.submit()
        advanceUntilIdle()
        storage.get(LoginViewModel.LOGIN_FIELD_KEY) shouldBe "user@example.com"
        storage.get(LoginViewModel.PASSWORD_KEY) shouldBe "hunter2"

        vm.onSavePasswordChange(false)
        vm.onLoginFieldChange("user@example.com")
        vm.onPasswordChange("hunter2")
        vm.submit()
        advanceUntilIdle()
        storage.get(LoginViewModel.LOGIN_FIELD_KEY) shouldBe null
        storage.get(LoginViewModel.PASSWORD_KEY) shouldBe null
    }

    @Test
    fun `init prefills credentials and ticks the checkbox when stored`() = runTest {
        val storage = InMemoryStorage()
        storage.put(LoginViewModel.LOGIN_FIELD_KEY, "saved@example.com")
        storage.put(LoginViewModel.PASSWORD_KEY, "savedpwd")
        val (vm, _) = newViewModel(this, validation = TokenValidation.Unauthorized(), storage = storage)
        advanceUntilIdle()
        vm.state.value.loginField shouldBe "saved@example.com"
        vm.state.value.password shouldBe "savedpwd"
        vm.state.value.savePassword shouldBe true
    }

    private fun newViewModel(
        scope: TestScope,
        validation: TokenValidation,
        storage: InMemoryStorage = InMemoryStorage(),
    ): Pair<LoginViewModel, SessionManager> {
        val transport = FakeTransport(validation)
        val coroutineScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        val manager = SessionManager(
            applicationScope = coroutineScope,
            secureStorage = storage,
            sessionFactory = { token -> DiscordSession(coroutineScope, token, transport) },
        )
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        val vm = LoginViewModel(ctx, manager, secureStorage = storage, externalScope = coroutineScope)
        return vm to manager
    }
}

internal class InMemoryStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
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

@Suppress("unused")
private fun fakeSelf(): UserSummary = UserSummary(
    id = UserId(1L),
    username = "self",
    globalName = "Self",
    discriminator = null,
    avatarHash = null,
    bot = false,
    system = false,
)
