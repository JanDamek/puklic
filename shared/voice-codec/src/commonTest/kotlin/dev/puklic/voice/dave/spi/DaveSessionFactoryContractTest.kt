package dev.puklic.voice.dave.spi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Contract tests for the DAVE SPI defined in `:shared:voice-codec/commonMain`. These
 * pin the surface the JVM `LibdaveDaveSessionFactory` adapter must satisfy and that the
 * upcoming FP-14h-7 promotion of `DefaultVoiceClient` will consume.
 *
 * No JVM types here — the SPI must be reachable from iosMain unchanged.
 */
class DaveSessionFactoryContractTest {

    @Test
    fun factory_returns_handle_that_exposes_state_and_close() = runTest {
        val factory = FakeDaveFactory(
            initialState = DaveSessionState.Active(epoch = 3L),
            frameEncryptorBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            frameDecryptorOutcome = byteArrayOf(0x01),
            pairwiseFingerprintBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
        )

        val handle = factory.create(
            channelId = "ch-1",
            userId = "u-1",
            sendBinary = { _, _ -> },
            sendJson = { _, _ -> },
        )

        handle.init() // must not throw

        val state = handle.state.value
        assertEquals(DaveSessionState.Active(epoch = 3L), state)

        val encryptor = assertNotNull(handle.frameEncryptor(ssrc = 1234))
        assertEquals(2, encryptor.encrypt(byteArrayOf(0x00)).size)

        val decryptor = assertNotNull(handle.frameDecryptor(userId = "u-2", ssrc = 1234))
        assertEquals(1, decryptor.decrypt(byteArrayOf(0xFF.toByte()))?.size)

        assertNotNull(handle.pairwiseFingerprint("u-2"))
        handle.handleBinaryOp(op = 27, payload = byteArrayOf(0x01))
        handle.handleJsonOp(op = 30, body = buildJsonObject { })
        handle.close()
        assertEquals(1, factory.closeCount)
    }

    @Test
    fun frame_encryptor_returns_null_when_backend_has_no_keys() = runTest {
        val factory = FakeDaveFactory(
            initialState = DaveSessionState.Connecting,
            frameEncryptorBytes = null,
            frameDecryptorOutcome = null,
            pairwiseFingerprintBytes = null,
        )
        val handle = factory.create("ch", "u", { _, _ -> }, { _, _ -> })
        assertNull(handle.frameEncryptor(ssrc = 1))
        assertNull(handle.frameDecryptor(userId = "remote", ssrc = 1))
        assertNull(handle.pairwiseFingerprint("remote"))
    }

    @Test
    fun state_changes_propagate_through_handle() = runTest {
        val sharedState = MutableStateFlow<DaveSessionState>(DaveSessionState.Connecting)
        val handle = MutableStateDaveHandle(sharedState)
        sharedState.value = DaveSessionState.Active(epoch = 7L)
        assertEquals(DaveSessionState.Active(epoch = 7L), handle.state.value)
        sharedState.value = DaveSessionState.Disabled("server-disabled")
        assertEquals(DaveSessionState.Disabled("server-disabled"), handle.state.value)
    }

    @Test
    fun spi_state_types_have_value_semantics() {
        assertEquals(DaveSessionState.Active(1L), DaveSessionState.Active(1L))
        assertEquals(DaveSessionState.Disabled("x"), DaveSessionState.Disabled("x"))
        assertSame(DaveSessionState.Connecting, DaveSessionState.Connecting)
    }
}

private class FakeDaveFactory(
    private val initialState: DaveSessionState,
    private val frameEncryptorBytes: ByteArray?,
    private val frameDecryptorOutcome: ByteArray?,
    private val pairwiseFingerprintBytes: ByteArray?,
) : DaveSessionFactory {
    var closeCount: Int = 0
        private set

    override fun create(
        channelId: String,
        userId: String,
        sendBinary: suspend (op: Int, payload: ByteArray) -> Unit,
        sendJson: suspend (op: Int, body: JsonElement) -> Unit,
    ): DaveSessionHandle = object : DaveSessionHandle {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<DaveSessionState> = _state.asStateFlow()
        override suspend fun init() { /* test stub */ }
        override suspend fun frameEncryptor(ssrc: Int) = frameEncryptorBytes?.let { bytes ->
            object : FrameEncryptor {
                override fun encrypt(plaintext: ByteArray) = bytes
                override fun close() {}
            }
        }
        override suspend fun frameDecryptor(userId: String, ssrc: Int) = frameDecryptorOutcome?.let { bytes ->
            object : FrameDecryptor {
                override fun decrypt(ciphertext: ByteArray) = bytes
                override fun close() {}
            }
        }
        override suspend fun handleBinaryOp(op: Int, payload: ByteArray) {}
        override suspend fun handleJsonOp(op: Int, body: JsonElement) {
            require(body is JsonObject)
        }
        override suspend fun pairwiseFingerprint(remoteUserId: String) = pairwiseFingerprintBytes
        override fun close() { closeCount += 1 }
    }
}

private class MutableStateDaveHandle(
    private val backing: MutableStateFlow<DaveSessionState>,
) : DaveSessionHandle {
    override val state: StateFlow<DaveSessionState> = backing.asStateFlow()
    override suspend fun init() {}
    override suspend fun frameEncryptor(ssrc: Int): FrameEncryptor? = null
    override suspend fun frameDecryptor(userId: String, ssrc: Int): FrameDecryptor? = null
    override suspend fun handleBinaryOp(op: Int, payload: ByteArray) {}
    override suspend fun handleJsonOp(op: Int, body: JsonElement) {}
    override suspend fun pairwiseFingerprint(remoteUserId: String): ByteArray? = null
    override fun close() {}
}
