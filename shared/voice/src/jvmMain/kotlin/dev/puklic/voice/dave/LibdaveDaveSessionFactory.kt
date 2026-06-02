package dev.puklic.voice.dave

import dev.puklic.voice.dave.spi.DaveSessionFactory
import dev.puklic.voice.dave.spi.DaveSessionHandle
import dev.puklic.voice.dave.spi.DaveSessionState
import dev.puklic.voice.dave.spi.FrameDecryptor as SpiFrameDecryptor
import dev.puklic.voice.dave.spi.FrameEncryptor as SpiFrameEncryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * JVM concrete factory for [DaveSessionFactory]. Wraps the libdave / Wire core-crypto
 * backed [DaveSession] (from `:shared:voice-dave`) behind the KMP-clean SPI declared
 * in `:shared:voice-codec/commonMain` (`dev.puklic.voice.dave.spi.*`).
 *
 * Constructed by `:desktop:app` `DependencyGraph` (the GPL desktop build); App Store
 * wiring layers pass null instead.
 *
 * Per architect plan `docs/03_infrastructure/architect-reports/2026-06-02-fp14h-6-blocked.md`.
 *
 * @param applicationScope long-lived scope used only to bridge the inner
 *   `DaveSession.state` StateFlow into the SPI-exposed [DaveSessionHandle.state]. The
 *   handle's own lifetime is bound to the voice call; the bridge job is cancelled in
 *   [DaveSessionHandle.close].
 */
public class LibdaveDaveSessionFactory(
    private val applicationScope: CoroutineScope,
) : DaveSessionFactory {

    override fun create(
        channelId: String,
        userId: String,
        sendBinary: suspend (op: Int, payload: ByteArray) -> Unit,
        sendJson: suspend (op: Int, body: JsonElement) -> Unit,
    ): DaveSessionHandle {
        val session = DaveSession(
            mlsClient = mlsClient(),
            channelId = channelId,
            userId = userId,
            sendBinary = sendBinary,
            // DaveSession ships JSON as a pre-serialized String; the SPI exposes a typed
            // JsonElement to keep the contract KMP-friendly (no String round-trip in the
            // caller). Bridge by parsing back to JsonElement on the way out.
            sendJson = { op, jsonString ->
                sendJson(op, kotlinx.serialization.json.Json.parseToJsonElement(jsonString))
            },
        )
        return LibdaveDaveSessionHandle(session, applicationScope)
    }
}

private class LibdaveDaveSessionHandle(
    private val session: DaveSession,
    applicationScope: CoroutineScope,
) : DaveSessionHandle {

    private val _state = MutableStateFlow<DaveSessionState>(DaveSessionState.Connecting)
    override val state: StateFlow<DaveSessionState> = _state.asStateFlow()

    private val bridgeJob: Job = applicationScope.launch {
        session.state.collect { inner ->
            _state.value = when (inner) {
                DaveSession.State.Idle,
                DaveSession.State.Initialized,
                DaveSession.State.Reinitializing,
                is DaveSession.State.PreparingTransition,
                -> DaveSessionState.Connecting
                is DaveSession.State.Active -> DaveSessionState.Active(inner.currentEpoch)
                is DaveSession.State.Disabled -> DaveSessionState.Disabled(inner.reason)
            }
        }
    }

    override suspend fun init() = session.init()

    override suspend fun frameEncryptor(ssrc: Int): SpiFrameEncryptor? =
        session.frameEncryptor(ssrc)?.let { inner ->
            object : SpiFrameEncryptor {
                override fun encrypt(plaintext: ByteArray): ByteArray = inner.encrypt(plaintext)
                override fun close() = inner.close()
            }
        }

    override suspend fun frameDecryptor(userId: String, ssrc: Int): SpiFrameDecryptor? =
        session.frameDecryptor(userId, ssrc)?.let { inner ->
            object : SpiFrameDecryptor {
                override fun decrypt(ciphertext: ByteArray): ByteArray? = inner.decrypt(ciphertext)
                override fun close() = inner.close()
            }
        }

    override suspend fun handleBinaryOp(op: Int, payload: ByteArray) =
        session.handleBinaryOp(op, payload)

    override suspend fun handleJsonOp(op: Int, body: JsonElement) =
        session.handleJsonOp(op, body)

    override suspend fun pairwiseFingerprint(remoteUserId: String): ByteArray? =
        session.pairwiseFingerprint(remoteUserId)

    override fun close() {
        bridgeJob.cancel()
        session.close()
    }
}
