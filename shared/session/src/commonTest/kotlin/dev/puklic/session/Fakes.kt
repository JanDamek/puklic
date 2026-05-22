package dev.puklic.session

import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
import dev.puklic.platform.SecureStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String) { map.remove(key) }
    override suspend fun list(): List<String> = map.keys.toList()
}

internal class FakeSessionTransport(
    var validation: TokenValidation = TokenValidation.Ok(
        UserSummary(UserId(42L), "self", "Self", null, null, bot = false, system = false),
    ),
) : SessionTransport {
    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(replay = 0, extraBufferCapacity = 16)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()

    var connectCalls = 0
    var disconnectCalls = 0

    override suspend fun validateToken(token: String): TokenValidation = validation
    override suspend fun connectGateway(token: String) { connectCalls++ }
    override suspend fun disconnectGateway() { disconnectCalls++ }

    suspend fun emit(event: GatewayLifecycleEvent) { _lifecycle.emit(event) }
}
