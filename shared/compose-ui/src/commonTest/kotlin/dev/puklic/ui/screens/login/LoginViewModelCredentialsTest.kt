package dev.puklic.ui.screens.login

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.platform.SecureStorage
import dev.puklic.session.CredentialsLogin
import dev.puklic.session.CredentialsLoginResult
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
class LoginViewModelCredentialsTest {

    @Test
    fun `selectMode switches to credentials and clears errors`() = runTest {
        val vm = newViewModel(this, CredentialsLoginResult.Error("nope"))
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.state.value.mode shouldBe LoginMode.CREDENTIALS
        vm.state.value.error shouldBe null
    }

    @Test
    fun `bad credentials populate user-visible error`() = runTest {
        val vm = newViewModel(this, CredentialsLoginResult.Error("Login or password is invalid."))
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.onLoginFieldChange("user@example.com")
        vm.onPasswordChange("wrong")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.submitting shouldBe false
        (vm.state.value.error != null) shouldBe true
        vm.state.value.error!!.lowercase().contains("password") shouldBe true
    }

    @Test
    fun `captcha required opens inline captcha widget without surfacing error`() = runTest {
        val vm = newViewModel(this, CredentialsLoginResult.CaptchaRequired(sitekey = "sk-1", service = "hcaptcha"))
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.onLoginFieldChange("user")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.submitting shouldBe false
        vm.state.value.captchaPending shouldBe true
        vm.state.value.captchaSitekey shouldBe "sk-1"
        vm.state.value.captchaService shouldBe "hcaptcha"
        vm.state.value.error shouldBe null
    }

    @Test
    fun `captcha solved re-submits credentials with captchaKey and advances to MFA`() = runTest {
        // Second-stage result is MfaRequired so finalizeToken (which routes through the
        // session transport's token validation) is not exercised; we only verify the
        // captcha state clears and the captchaKey is forwarded.
        val fake = FakeLogin(
            CredentialsLoginResult.CaptchaRequired(sitekey = "sk-2", service = "hcaptcha"),
            captchaRetryResult = CredentialsLoginResult.MfaRequired("tkt-after-captcha"),
        )
        val vm = newViewModelWithFake(this, fake)
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.onLoginFieldChange("user")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.captchaPending shouldBe true

        vm.onCaptchaSolved("h-captcha-token")
        advanceUntilIdle()
        vm.state.value.captchaPending shouldBe false
        vm.state.value.mfaTicket shouldBe "tkt-after-captcha"
        vm.state.value.error shouldBe null
        fake.lastCaptchaKey shouldBe "h-captcha-token"
    }

    @Test
    fun `cancelCaptcha drops captcha state and returns to credentials form`() = runTest {
        val vm = newViewModel(this, CredentialsLoginResult.CaptchaRequired(sitekey = "sk-3", service = "hcaptcha"))
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.onLoginFieldChange("user")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.captchaPending shouldBe true

        vm.cancelCaptcha()
        vm.state.value.captchaPending shouldBe false
        vm.state.value.captchaSitekey shouldBe null
    }

    @Test
    fun `mfa required opens TOTP step and clears password`() = runTest {
        val vm = newViewModel(this, CredentialsLoginResult.MfaRequired("tkt-9"))
        vm.selectMode(LoginMode.CREDENTIALS)
        vm.onLoginFieldChange("user")
        vm.onPasswordChange("pw")
        vm.submit()
        advanceUntilIdle()
        vm.state.value.mfaTicket shouldBe "tkt-9"
        vm.state.value.password shouldBe ""
        vm.state.value.canSubmitCredentials shouldBe false
    }

    private fun newViewModel(
        scope: TestScope,
        loginResult: CredentialsLoginResult,
    ): LoginViewModel = newViewModelWithFake(scope, FakeLogin(loginResult))

    private fun newViewModelWithFake(scope: TestScope, fake: FakeLogin): LoginViewModel {
        val storage = CredsInMemoryStorage()
        val transport = CredsFakeTransport()
        val coroutineScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
        val manager = SessionManager(
            applicationScope = coroutineScope,
            secureStorage = storage,
            sessionFactory = { token -> DiscordSession(coroutineScope, token, transport) },
            credentialsLogin = fake,
        )
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return LoginViewModel(ctx, manager, externalScope = coroutineScope)
    }
}

private class CredsInMemoryStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun list(): List<String> = map.keys.toList()
}

private class CredsFakeTransport : SessionTransport {
    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(replay = 0, extraBufferCapacity = 8)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()
    override suspend fun validateToken(token: String): TokenValidation = TokenValidation.Unauthorized
    override suspend fun connectGateway(token: String) {}
    override suspend fun disconnectGateway() {}
}

private class FakeLogin(
    private val result: CredentialsLoginResult,
    private val captchaRetryResult: CredentialsLoginResult? = null,
) : CredentialsLogin {
    var lastCaptchaKey: String? = null
        private set

    override suspend fun login(
        loginIdentifier: String,
        password: String,
        captchaKey: String?,
    ): CredentialsLoginResult {
        lastCaptchaKey = captchaKey
        return if (captchaKey != null && captchaRetryResult != null) captchaRetryResult else result
    }

    override suspend fun completeMfa(ticket: String, code: String): CredentialsLoginResult = result
}
