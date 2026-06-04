package dev.puklic.session

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SessionManagerCredentialsTest {

    @Test
    fun credentials_happy_path_stores_token_and_starts_session() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val login = FakeCredentialsLogin(
            firstResult = CredentialsLoginResult.Success("token-from-pw"),
        )
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
            credentialsLogin = login,
        )

        val result = mgr.startSessionWithCredentials("user@example.com", "secret")
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe LoginOutcome.Success
        storage.get(SessionManager.TOKEN_KEY) shouldBe "token-from-pw"
        (mgr.activeSession.value != null) shouldBe true
        parent.cancel()
    }

    @Test
    fun credentials_captcha_surfaces_outcome_without_token() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val login = FakeCredentialsLogin(
            firstResult = CredentialsLoginResult.CaptchaRequired(
                sitekey = "site-x",
                service = "hcaptcha",
                rqdata = "RQDATA_BLOB",
                rqtoken = "RQTOKEN",
                sessionId = "SID-1",
            ),
        )
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
            credentialsLogin = login,
        )

        val result = mgr.startSessionWithCredentials("user", "pw")
        result.getOrThrow() shouldBe LoginOutcome.CaptchaRequired(
            sitekey = "site-x",
            service = "hcaptcha",
            rqdata = "RQDATA_BLOB",
            rqtoken = "RQTOKEN",
            sessionId = "SID-1",
        )
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        mgr.activeSession.value shouldBe null
        parent.cancel()
    }

    @Test
    fun credentials_captcha_retry_forwards_key_and_rqtoken() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val login = FakeCredentialsLogin(
            firstResult = CredentialsLoginResult.CaptchaRequired(
                sitekey = "site-x",
                service = "hcaptcha",
                rqdata = "RQDATA_BLOB",
                rqtoken = "RQTOKEN",
                sessionId = "SID-1",
            ),
            captchaRetryResult = CredentialsLoginResult.Success("token-after-captcha"),
        )
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
            credentialsLogin = login,
        )

        val result = mgr.startSessionWithCredentials(
            "user",
            "pw",
            captchaKey = "tok",
            captchaRqtoken = "rqt",
            captchaSessionId = "sid",
        )
        result.getOrThrow() shouldBe LoginOutcome.Success
        login.lastCaptchaKey shouldBe "tok"
        login.lastCaptchaRqtoken shouldBe "rqt"
        login.lastCaptchaSessionId shouldBe "sid"
        storage.get(SessionManager.TOKEN_KEY) shouldBe "token-after-captcha"
        parent.cancel()
    }

    @Test
    fun credentials_mfa_then_complete_finalizes_session() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val login = FakeCredentialsLogin(
            firstResult = CredentialsLoginResult.MfaRequired("tkt-1"),
            mfaResult = CredentialsLoginResult.Success("token-after-mfa"),
        )
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
            credentialsLogin = login,
        )

        val first = mgr.startSessionWithCredentials("user", "pw").getOrThrow()
        first.shouldBeInstanceOf<LoginOutcome.MfaRequired>()
        first.ticket shouldBe "tkt-1"

        val finalize = mgr.completeMfa("tkt-1", "123456")
        finalize.isSuccess shouldBe true
        storage.get(SessionManager.TOKEN_KEY) shouldBe "token-after-mfa"
        (mgr.activeSession.value != null) shouldBe true
        parent.cancel()
    }

    @Test
    fun credentials_bad_password_returns_failure() = runTest {
        val parent = Job()
        val storage = FakeSecureStorage()
        val transport = FakeSessionTransport()
        val login = FakeCredentialsLogin(
            firstResult = CredentialsLoginResult.Error("Login or password is invalid."),
        )
        val mgr = SessionManager(
            CoroutineScope(coroutineContext + parent),
            storage,
            sessionFactory = { t -> DiscordSession(CoroutineScope(coroutineContext + parent), t, transport) },
            credentialsLogin = login,
        )

        val result = mgr.startSessionWithCredentials("user", "wrong")
        result.isFailure shouldBe true
        (result.exceptionOrNull() is IllegalArgumentException) shouldBe true
        storage.get(SessionManager.TOKEN_KEY) shouldBe null
        parent.cancel()
    }
}

private class FakeCredentialsLogin(
    private val firstResult: CredentialsLoginResult,
    private val mfaResult: CredentialsLoginResult = CredentialsLoginResult.Error("no mfa configured"),
    private val captchaRetryResult: CredentialsLoginResult? = null,
) : CredentialsLogin {
    var lastCaptchaKey: String? = null
    var lastCaptchaRqtoken: String? = null
    var lastCaptchaSessionId: String? = null

    override suspend fun login(
        loginIdentifier: String,
        password: String,
        captchaKey: String?,
        captchaRqtoken: String?,
        captchaSessionId: String?,
    ): CredentialsLoginResult {
        lastCaptchaKey = captchaKey
        lastCaptchaRqtoken = captchaRqtoken
        lastCaptchaSessionId = captchaSessionId
        return if (captchaKey != null && captchaRetryResult != null) captchaRetryResult else firstResult
    }

    override suspend fun completeMfa(ticket: String, code: String): CredentialsLoginResult = mfaResult
}
