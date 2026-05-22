package dev.puklic.desktop

import dev.puklic.session.GatewayLifecycleEvent
import dev.puklic.session.SessionTransport
import dev.puklic.session.TokenValidation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 1 placeholder SessionTransport. The real implementation lands in step 15-16
 * once :shared:protocol-discord exposes a public adapter that bridges its internal
 * REST/Gateway clients to the SessionTransport interface in :shared:session.
 *
 * Until then, this transport always reports the token as unauthorized — that surfaces a
 * helpful error message on the LoginScreen, which is exactly the visible behavior the
 * Phase 1 smoke run needs to demonstrate (window opens, login screen renders, submit
 * triggers state transitions).
 */
public class PlaceholderTransport : SessionTransport {
    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(replay = 0, extraBufferCapacity = 4)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()

    override suspend fun validateToken(token: String): TokenValidation = TokenValidation.Unauthorized
    override suspend fun connectGateway(token: String) = Unit
    override suspend fun disconnectGateway() = Unit
}
