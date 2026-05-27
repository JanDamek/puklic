package dev.puklic.session

import dev.puklic.domain.UserSummary
import dev.puklic.repositories.Orchestrators
import dev.puklic.voice.NoOpVoiceClient
import dev.puklic.voice.VoiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * A single Discord session (one logged-in account). Drives the gateway state machine and exposes
 * a [SessionState] StateFlow for the UI.
 *
 * Lifecycle (per `data-flow.md`):
 *  1. `connect()` → ValidatingToken → REST GET /users/@me.
 *  2. On 200 → Connecting → gateway IDENTIFY.
 *  3. On gateway READY → Connected(sessionId, selfUser).
 *  4. On 401 anywhere → TokenInvalid.
 *  5. `disconnect()` → cancels [sessionScope] (cascades to gateway + orchestrators).
 *
 * The session owns its own [CoroutineScope] derived from [applicationScope]; cancelling the session
 * cancels that scope without affecting the application scope.
 */
public class DiscordSession(
    public val applicationScope: CoroutineScope,
    private val token: String,
    public val transport: SessionTransport,
    public val orchestrators: Orchestrators? = null,
    public val voiceClient: VoiceClient = NoOpVoiceClient(),
    public val dmCreator: DmCreator? = null,
) {
    private val sessionJob: Job = SupervisorJob(applicationScope.coroutineContext[Job])
    private val context: CoroutineContext = applicationScope.coroutineContext + sessionJob
    public val sessionScope: CoroutineScope = CoroutineScope(context)

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _selfUser = MutableStateFlow<UserSummary?>(null)
    public val selfUser: StateFlow<UserSummary?> = _selfUser.asStateFlow()

    private var lifecycleJob: Job? = null

    /** Validate the token, connect the gateway, await READY. */
    public suspend fun connect() {
        _state.value = SessionState.ValidatingToken
        when (val validation = transport.validateToken(token)) {
            is TokenValidation.Ok -> {
                _selfUser.value = validation.selfUser
                _state.value = SessionState.Connecting(attempt = 1)
                lifecycleJob?.cancel()
                lifecycleJob = sessionScope.launch {
                    transport.lifecycle.collect { event ->
                        _state.value = mapLifecycle(event, validation.selfUser)
                    }
                }
                transport.connectGateway(token)
            }
            is TokenValidation.Unauthorized -> _state.value = SessionState.TokenInvalid
            is TokenValidation.TransportError -> _state.value = SessionState.Failed(validation.message)
        }
    }

    private fun mapLifecycle(event: GatewayLifecycleEvent, self: UserSummary): SessionState = when (event) {
        is GatewayLifecycleEvent.Connected -> SessionState.Connected(event.sessionId, self)
        is GatewayLifecycleEvent.Disconnected -> SessionState.Disconnected
        is GatewayLifecycleEvent.TokenInvalid -> SessionState.TokenInvalid
        is GatewayLifecycleEvent.Failed -> SessionState.Failed(event.reason)
        is GatewayLifecycleEvent.Reconnecting -> SessionState.Reconnecting(event.secondsUntilRetry)
    }

    /** Cancel the session scope. The transport is stopped first to release the WebSocket cleanly. */
    public suspend fun disconnect() {
        transport.disconnectGateway()
        sessionScope.cancel()
        _state.value = SessionState.Disconnected
    }
}
