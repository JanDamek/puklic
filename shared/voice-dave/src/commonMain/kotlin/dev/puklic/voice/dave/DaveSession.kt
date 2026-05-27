package dev.puklic.voice.dave

import co.touchlab.kermit.Logger
import dev.puklic.voice.dave.gateway.DaveOp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * High-level DAVE protocol state machine. Drives MLS handshake transitions
 * over the voice gateway (opcodes 21-31) and exposes per-(SSRC, generation)
 * frame keys for the encrypt/decrypt pipelines (Phase 3.1d).
 *
 * One [DaveSession] per voice channel session. Thread-safe via an internal
 * [Mutex]; callers may invoke from any coroutine context.
 *
 * Construction injects the gateway send paths so this class stays
 * commonMain-pure (no Ktor / no platform networking).
 *
 * State machine (see [State] for nodes):
 *
 * ```
 *   Idle ─init()─> Initialized ──┐
 *                                │ (server) PREPARE_TRANSITION
 *                                ▼
 *                       PreparingTransition ─(server) EXECUTE_TRANSITION─> Active
 *                                │
 *                                ▼
 *                         (any op) INVALID_COMMIT_WELCOME ─> Reinitializing
 *                                │
 *                                ▼
 *                                 init()
 * ```
 *
 * `Disabled` is reached when the server advertises a protocol version we don't
 * support — the call falls back to non-DAVE per-hop AEAD only.
 */
@Suppress("LongParameterList")
public class DaveSession(
    private val mlsClient: MlsClient,
    private val channelId: String,
    private val userId: String,
    private val sendBinary: suspend (op: Int, payload: ByteArray) -> Unit,
    private val sendJson: suspend (op: Int, json: String) -> Unit,
) {
    private val logger = Logger.withTag("dave.Session")
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var groupId: String? = null

    suspend fun init(): Unit = mutex.withLock {
        mlsClient.init(userId)
        _state.value = State.Initialized
    }

    /**
     * Handle a JSON-shaped DAVE op (21-24, 31) from the voice gateway. The
     * caller is expected to pre-dispatch by op number; we do the body parse
     * + side effects here.
     */
    suspend fun handleJsonOp(op: Int, jsonBody: JsonElement): Unit = mutex.withLock {
        when (op) {
            DaveOp.PROTOCOL_PREPARE_TRANSITION -> handlePrepareTransition(jsonBody)
            DaveOp.PROTOCOL_EXECUTE_TRANSITION -> handleExecuteTransition()
            DaveOp.PROTOCOL_PREPARE_EPOCH -> handlePrepareEpoch(jsonBody)
            DaveOp.MLS_INVALID_COMMIT_WELCOME -> handleInvalidCommitWelcome()
            else -> logger.w { "handleJsonOp: unexpected op=$op" }
        }
    }

    /**
     * Handle a binary-shaped DAVE op (25-30) from the voice gateway. Caller has
     * already stripped the (seq, op) header via [gateway.DaveBinaryFrame.parse].
     */
    suspend fun handleBinaryOp(op: Int, payload: ByteArray): Unit = mutex.withLock {
        when (op) {
            DaveOp.MLS_EXTERNAL_SENDER_PACKAGE -> mlsClient.processExternalSender(channelId, payload)
            DaveOp.MLS_WELCOME -> {
                val joined = mlsClient.processWelcome(payload)
                groupId = joined
            }
            DaveOp.MLS_PROPOSALS,
            DaveOp.MLS_ANNOUNCE_COMMIT_TRANSITION,
            -> {
                val gid = groupId
                if (gid == null) {
                    logger.w { "handleBinaryOp op=$op: no groupId yet — ignoring." }
                } else {
                    mlsClient.processCommit(gid, payload)
                }
            }
            else -> logger.w { "handleBinaryOp: unexpected op=$op" }
        }
    }

    /**
     * Derive a per-(SSRC, generation) frame key of [length] bytes for the
     * encrypt/decrypt pipelines. Returns null if the group is not joined yet.
     *
     * See [MlsClient.exportSecret] for the wire-incompat caveat (Wire 4.2.0
     * limitation).
     */
    suspend fun deriveFrameKey(ssrc: Int, generation: Int, length: Int = FRAME_KEY_LEN): ByteArray? {
        val gid = mutex.withLock { groupId } ?: return null
        val ctx = ByteArray(CTX_LEN).also { buf ->
            writeBigEndianInt(buf, 0, ssrc)
            writeBigEndianInt(buf, Int.SIZE_BYTES, generation)
        }
        return mlsClient.exportSecret(gid, DAVE_FRAME_LABEL, ctx, length)
    }

    /**
     * Build a [FrameEncryptor] for the local user's outbound audio on [ssrc].
     * Returns null when DAVE is disabled (e.g. Wire backend, pre-join, or
     * server-advertised unsupported version). Callers must pass-through Opus
     * frames unchanged in that case.
     */
    suspend fun frameEncryptor(ssrc: Int): FrameEncryptor? = mutex.withLock {
        if (_state.value is State.Disabled) return null
        mlsClient.frameEncryptor(ssrc)
    }

    /**
     * Build a [FrameDecryptor] for inbound audio from [userId] on [ssrc]. Same
     * null semantics as [frameEncryptor].
     */
    suspend fun frameDecryptor(userId: String, ssrc: Int): FrameDecryptor? = mutex.withLock {
        if (_state.value is State.Disabled) return null
        mlsClient.frameDecryptor(userId, ssrc)
    }

    private suspend fun handlePrepareTransition(jsonBody: JsonElement) {
        val obj = jsonBody.jsonObject
        val proto = obj["protocol_version"]?.jsonPrimitive?.int ?: 0
        val transitionId = obj["transition_id"]?.jsonPrimitive?.int ?: 0
        if (proto != SUPPORTED_PROTOCOL_VERSION) {
            _state.value = State.Disabled("Unsupported DAVE protocol_version=$proto")
            return
        }
        val reply = buildJsonObject {
            put("transition_id", JsonPrimitive(transitionId))
        }
        sendJson(DaveOp.PROTOCOL_READY_FOR_TRANSITION, reply.toString())
        _state.value = State.PreparingTransition(transitionId)
    }

    private suspend fun handleExecuteTransition() {
        val gid = groupId
        if (gid == null) {
            logger.w { "EXECUTE_TRANSITION before group join — staying in current state." }
            return
        }
        _state.value = State.Active(currentEpoch = mlsClient.currentEpoch(gid))
    }

    private suspend fun handlePrepareEpoch(@Suppress("UNUSED_PARAMETER") jsonBody: JsonElement) {
        val kp = mlsClient.generateKeyPackage()
        sendBinary(DaveOp.MLS_KEY_PACKAGE, kp)
    }

    private suspend fun handleInvalidCommitWelcome() {
        _state.value = State.Reinitializing
        groupId = null
        mlsClient.init(userId)
        _state.value = State.Initialized
    }

    /**
     * Pairwise fingerprint bytes between the local identity and [remoteUserId]
     * for the active group. Returns null when DAVE is not in [State.Active]
     * or when the underlying [MlsClient] cannot produce one. Used by the
     * "Verify call" UI to render a Short Authentication String via
     * [dev.puklic.voice.dave.sas.SasFormatter].
     */
    suspend fun pairwiseFingerprint(remoteUserId: String): ByteArray? {
        val active = mutex.withLock { _state.value is State.Active }
        if (!active) return null
        return mlsClient.pairwiseFingerprint(remoteUserId)
    }

    /** Release the underlying MLS client + native handles. Idempotent. */
    fun close() {
        runCatching { mlsClient.close() }
    }

    sealed interface State {
        data object Idle : State
        data object Initialized : State
        data class PreparingTransition(val transitionId: Int) : State
        data class Active(val currentEpoch: Long) : State
        data object Reinitializing : State
        data class Disabled(val reason: String) : State
    }

    private companion object {
        const val DAVE_FRAME_LABEL = "Discord Secure Frames v0"
        const val SUPPORTED_PROTOCOL_VERSION = 1
        const val FRAME_KEY_LEN = 32          // ChaCha20-Poly1305 key length
        const val CTX_LEN = 8                 // 4 B SSRC || 4 B generation, both BE

        fun writeBigEndianInt(buf: ByteArray, offset: Int, v: Int) {
            buf[offset] = ((v ushr 24) and 0xFF).toByte()
            buf[offset + 1] = ((v ushr 16) and 0xFF).toByte()
            buf[offset + 2] = ((v ushr 8) and 0xFF).toByte()
            buf[offset + 3] = (v and 0xFF).toByte()
        }
    }
}
