package dev.puklic.session

import dev.puklic.platform.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level singleton owning the active [DiscordSession]. Stores tokens in the platform
 * [SecureStorage] (keychain / libsecret / keystore — never in cleartext on disk).
 *
 * Per `data-flow.md`:
 *  - `loadStoredSession()` on app start: if a token exists, hydrate UI from SQLite + start session.
 *  - `startSessionWithToken(token)` on login: validate → store token → start session.
 *  - `endSession(wipeToken)` on logout: stop session → optionally wipe token.
 */
public class SessionManager(
    @Suppress("unused") private val applicationScope: CoroutineScope,
    private val secureStorage: SecureStorage,
    private val sessionFactory: (token: String) -> DiscordSession,
    private val credentialsLogin: CredentialsLogin? = null,
) {
    private val _activeSession = MutableStateFlow<DiscordSession?>(null)
    public val activeSession: StateFlow<DiscordSession?> = _activeSession.asStateFlow()

    private val _bootstrap = MutableStateFlow(BootstrapPhase.Idle)
    /**
     * Phase of the auto-restore flow triggered by [loadStoredSession].
     *  - [BootstrapPhase.Idle]: never started (or no auto-restore in this process).
     *  - [BootstrapPhase.Loading]: reading SecureStorage / validating token.
     *  - [BootstrapPhase.Done]: finished — observe [activeSession] for the outcome.
     */
    public val bootstrap: StateFlow<BootstrapPhase> = _bootstrap.asStateFlow()

    public suspend fun startSessionWithToken(token: String): Result<Unit> {
        val session = sessionFactory(token)
        session.connect()
        return when (val s = session.state.value) {
            is SessionState.TokenInvalid -> {
                Result.failure(IllegalArgumentException("Token rejected by Discord (401)"))
            }
            is SessionState.Failed -> {
                Result.failure(IllegalStateException("Session failed: ${s.reason}"))
            }
            else -> {
                secureStorage.put(TOKEN_KEY, token)
                _activeSession.value = session
                Result.success(Unit)
            }
        }
    }

    /** Returns true if a stored token was found and the session was started successfully. */
    public suspend fun loadStoredSession(): Boolean {
        _bootstrap.value = BootstrapPhase.Loading
        try {
            val token = secureStorage.get(TOKEN_KEY) ?: return false
            val result = startSessionWithToken(token)
            if (result.isFailure) {
                secureStorage.remove(TOKEN_KEY)
                return false
            }
            return true
        } finally {
            _bootstrap.value = BootstrapPhase.Done
        }
    }

    /**
     * Sign in via email-or-username + password. On [LoginOutcome.Success] the resulting token
     * is persisted and a session is started exactly as in [startSessionWithToken].
     *
     * Requires a [credentialsLogin] collaborator wired at construction; otherwise fails.
     */
    public suspend fun startSessionWithCredentials(
        login: String,
        password: String,
        captchaKey: String? = null,
        captchaRqtoken: String? = null,
        captchaSessionId: String? = null,
    ): Result<LoginOutcome> {
        val collaborator = credentialsLogin
            ?: return Result.failure(IllegalStateException("Credentials login not configured"))
        return when (val outcome = collaborator.login(login, password, captchaKey, captchaRqtoken, captchaSessionId)) {
            is CredentialsLoginResult.Success -> finalizeToken(outcome.token).map { LoginOutcome.Success }
            is CredentialsLoginResult.MfaRequired -> Result.success(LoginOutcome.MfaRequired(outcome.ticket))
            is CredentialsLoginResult.CaptchaRequired ->
                Result.success(
                    LoginOutcome.CaptchaRequired(
                        sitekey = outcome.sitekey,
                        service = outcome.service,
                        rqdata = outcome.rqdata,
                        rqtoken = outcome.rqtoken,
                        sessionId = outcome.sessionId,
                    ),
                )
            is CredentialsLoginResult.Error -> Result.failure(IllegalArgumentException(outcome.message))
            is CredentialsLoginResult.Transport -> Result.failure(IllegalStateException(outcome.message))
        }
    }

    /**
     * Complete the MFA challenge started by [startSessionWithCredentials]. On success the token
     * is persisted and the session is started.
     */
    public suspend fun completeMfa(ticket: String, code: String): Result<Unit> {
        val collaborator = credentialsLogin
            ?: return Result.failure(IllegalStateException("Credentials login not configured"))
        return when (val outcome = collaborator.completeMfa(ticket, code)) {
            is CredentialsLoginResult.Success -> finalizeToken(outcome.token)
            is CredentialsLoginResult.MfaRequired ->
                Result.failure(IllegalStateException("Unexpected secondary MFA challenge"))
            is CredentialsLoginResult.CaptchaRequired ->
                Result.failure(IllegalStateException("Unexpected captcha after MFA"))
            is CredentialsLoginResult.Error -> Result.failure(IllegalArgumentException(outcome.message))
            is CredentialsLoginResult.Transport -> Result.failure(IllegalStateException(outcome.message))
        }
    }

    private suspend fun finalizeToken(token: String): Result<Unit> = startSessionWithToken(token)

    public suspend fun endSession(wipeToken: Boolean = false) {
        _activeSession.value?.disconnect()
        _activeSession.value = null
        if (wipeToken) secureStorage.remove(TOKEN_KEY)
    }

    public companion object {
        public const val TOKEN_KEY: String = "discord.token"
    }
}
