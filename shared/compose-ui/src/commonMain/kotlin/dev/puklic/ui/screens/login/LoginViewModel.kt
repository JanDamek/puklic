package dev.puklic.ui.screens.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Observable state of the [dev.puklic.ui.screens.login.LoginScreen].
 *
 * Per `docs/04_ui/screens.md` §LoginScreen "States" table:
 *  - Idle / Typing → distinguished by [token] non-empty
 *  - Validating → [submitting]
 *  - Error → [error] populated
 */
public data class LoginState(
    val token: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    public val canSubmit: Boolean get() = token.isNotBlank() && !submitting
}

/**
 * Decompose-style ViewModel for the login screen. Lifecycle is owned by Decompose via
 * [ComponentContext]; the coroutine scope is bound to the lifecycle.
 */
public class LoginViewModel(
    componentContext: ComponentContext,
    private val sessionManager: SessionManager,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LoginState())
    public val state: StateFlow<LoginState> = _state.asStateFlow()

    public fun onTokenChange(token: String) {
        _state.update { it.copy(token = token.trim(), error = null) }
    }

    public fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        scope.launch {
            val result = sessionManager.startSessionWithToken(current.token)
            if (result.isFailure) {
                _state.update { it.copy(submitting = false, error = result.exceptionOrNull()?.message ?: "Login failed") }
            } else {
                _state.update { it.copy(submitting = false, error = null) }
            }
        }
    }
}
