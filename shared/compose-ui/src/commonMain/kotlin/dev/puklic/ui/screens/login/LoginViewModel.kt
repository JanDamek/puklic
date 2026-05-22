package dev.puklic.ui.screens.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.session.LoginOutcome
import dev.puklic.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in mode the user has chosen on the [LoginScreen].
 */
public enum class LoginMode { TOKEN, CREDENTIALS }

/**
 * Observable state of the login screen.
 *
 *  - [mode] — active tab (token paste vs credentials).
 *  - [token] / [loginField] / [password] — form fields per mode.
 *  - [submitting] — request in flight.
 *  - [mfaTicket] — non-null when Discord challenged us; UI then shows the TOTP field.
 *  - [mfaCode] — user-entered 6-digit code.
 *  - [error] — user-facing error string (already mapped), per `docs/04_ui/screens.md`.
 */
public data class LoginState(
    val mode: LoginMode = LoginMode.TOKEN,
    val token: String = "",
    val loginField: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val mfaTicket: String? = null,
    val mfaCode: String = "",
    val error: String? = null,
) {
    public val canSubmitToken: Boolean get() = token.isNotBlank() && !submitting

    /** Back-compat alias for the legacy token-only API: equals [canSubmitToken] when in TOKEN mode. */
    public val canSubmit: Boolean
        get() = when (mode) {
            LoginMode.TOKEN -> canSubmitToken
            LoginMode.CREDENTIALS -> if (mfaTicket == null) canSubmitCredentials else canSubmitMfa
        }
    public val canSubmitCredentials: Boolean
        get() = loginField.isNotBlank() && password.isNotBlank() && !submitting && mfaTicket == null
    public val canSubmitMfa: Boolean
        get() = mfaTicket != null && mfaCode.isNotBlank() && !submitting
}

/**
 * Decompose-style ViewModel for the login screen. Owns the form state and dispatches the
 * appropriate [SessionManager] call for the active [LoginMode].
 *
 * Passwords are never logged or echoed back outside the state object the UI is rendering;
 * the field is masked in the UI via `PasswordVisualTransformation`.
 */
public class LoginViewModel(
    componentContext: ComponentContext,
    private val sessionManager: SessionManager,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LoginState())
    public val state: StateFlow<LoginState> = _state.asStateFlow()

    public fun selectMode(mode: LoginMode) {
        _state.update { it.copy(mode = mode, error = null, mfaTicket = null, mfaCode = "") }
    }

    public fun onTokenChange(token: String) {
        _state.update { it.copy(token = token.trim(), error = null) }
    }

    public fun onLoginFieldChange(value: String) {
        _state.update { it.copy(loginField = value.trim(), error = null) }
    }

    public fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    public fun onMfaCodeChange(value: String) {
        _state.update { it.copy(mfaCode = value.trim(), error = null) }
    }

    public fun submit() {
        val current = _state.value
        when (current.mode) {
            LoginMode.TOKEN -> submitToken(current)
            LoginMode.CREDENTIALS -> submitCredentials(current)
        }
    }

    private fun submitToken(current: LoginState) {
        if (!current.canSubmitToken) return
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val result = runCatching { sessionManager.startSessionWithToken(current.token) }
                .getOrElse { Result.failure(it) }
            if (result.isFailure) {
                _state.update { it.copy(submitting = false, error = mapTokenError(result.exceptionOrNull())) }
            } else {
                _state.update { it.copy(submitting = false, error = null) }
            }
        }
    }

    private fun submitCredentials(current: LoginState) {
        if (!current.canSubmitCredentials) return
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val result = runCatching {
                sessionManager.startSessionWithCredentials(current.loginField, current.password)
            }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is LoginOutcome.Success ->
                            _state.update { it.copy(submitting = false, error = null) }
                        is LoginOutcome.MfaRequired ->
                            _state.update {
                                it.copy(
                                    submitting = false,
                                    mfaTicket = outcome.ticket,
                                    password = "", // drop password once challenge is open
                                    error = null,
                                )
                            }
                        is LoginOutcome.CaptchaRequired ->
                            _state.update {
                                it.copy(submitting = false, error = ERROR_CAPTCHA)
                            }
                    }
                },
                onFailure = { cause ->
                    _state.update { it.copy(submitting = false, error = mapCredentialsError(cause)) }
                },
            )
        }
    }

    public fun submitMfa() {
        val current = _state.value
        val ticket = current.mfaTicket ?: return
        if (!current.canSubmitMfa) return
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val result = runCatching { sessionManager.completeMfa(ticket, current.mfaCode) }
                .getOrElse { Result.failure(it) }
            if (result.isFailure) {
                _state.update { it.copy(submitting = false, error = mapCredentialsError(result.exceptionOrNull())) }
            } else {
                _state.update {
                    it.copy(submitting = false, error = null, mfaTicket = null, mfaCode = "")
                }
            }
        }
    }

    private fun mapTokenError(throwable: Throwable?): String {
        if (throwable == null) return "Sign in failed"
        val msg = throwable.message.orEmpty().lowercase()
        return when {
            throwable is IllegalArgumentException -> "Token rejected by Discord"
            looksLikeNetwork(msg) -> "Cannot reach Discord. Check connection."
            else -> "Sign in failed"
        }
    }

    private fun mapCredentialsError(throwable: Throwable?): String {
        if (throwable == null) return "Sign in failed"
        val msg = throwable.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            throwable is IllegalArgumentException -> "Bad email/username or password."
            looksLikeNetwork(lower) -> "Cannot reach Discord. Check connection."
            else -> "Sign in failed."
        }
    }

    private fun looksLikeNetwork(msg: String): Boolean =
        "network" in msg || "connect" in msg || "timeout" in msg || "host" in msg || "unreachable" in msg

    private companion object {
        const val ERROR_CAPTCHA =
            "Captcha required. Switch to the Token tab and paste a token from your browser."
    }
}
