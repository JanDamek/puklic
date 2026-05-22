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
) {
    private val _activeSession = MutableStateFlow<DiscordSession?>(null)
    public val activeSession: StateFlow<DiscordSession?> = _activeSession.asStateFlow()

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
        val token = secureStorage.get(TOKEN_KEY) ?: return false
        val result = startSessionWithToken(token)
        if (result.isFailure) {
            secureStorage.remove(TOKEN_KEY)
            return false
        }
        return true
    }

    public suspend fun endSession(wipeToken: Boolean = false) {
        _activeSession.value?.disconnect()
        _activeSession.value = null
        if (wipeToken) secureStorage.remove(TOKEN_KEY)
    }

    public companion object {
        public const val TOKEN_KEY: String = "discord.token"
    }
}
