package dev.puklic.voice.dave.spi

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * One-shot DAVE frame encryptor as seen by the voice client.
 *
 * The concrete (libdave-backed) implementation lives in `:shared:voice-dave/jvmMain`
 * and is reachable from `:shared:voice/jvmMain`. App Store builds pass null factories
 * (see [DaveSessionFactory]) and therefore never load this interface.
 *
 * NOT thread-safe; one instance per (session, SSRC) on the producer coroutine.
 *
 * Per architect plan `docs/03_infrastructure/architect-reports/2026-06-02-fp14h-6-blocked.md`
 * (SPI definition for FP-14h-7 promotion of DefaultVoiceClient to commonMain).
 */
public interface FrameEncryptor {
    /** Encrypt one Opus media frame; returns ciphertext (Opus + DAVE trailer). */
    public fun encrypt(plaintext: ByteArray): ByteArray

    /** Release native handles. Idempotent. */
    public fun close()
}

/** One-shot DAVE frame decryptor. NOT thread-safe; one per (sender userId, SSRC). */
public interface FrameDecryptor {
    /** Decrypt one ciphertext frame; returns plaintext or null on integrity failure. */
    public fun decrypt(ciphertext: ByteArray): ByteArray?

    /** Release native handles. Idempotent. */
    public fun close()
}

/**
 * UI-facing DAVE state. Maps directly onto [dev.puklic.voice.DaveUiState] but kept
 * separate here so the SPI surface in :shared:voice-codec doesn't pull
 * :shared:voice-api transitively for callers that just want to observe DAVE state.
 *
 * Adapters owned by the wiring layer translate to the public UI type.
 */
public sealed interface DaveSessionState {
    public data object Connecting : DaveSessionState
    public data class Active(public val epoch: Long) : DaveSessionState
    public data class Disabled(public val reason: String) : DaveSessionState
}

/**
 * Opaque DAVE session handle the voice client uses for per-frame crypto, gateway
 * routing, and lifecycle.
 *
 * Concrete implementations are JVM-only (libdave / Wire core-crypto). App Store
 * builds receive a null [DaveSessionFactory] and never see this type at runtime;
 * Discord's voice protocol falls back to xsalsa20_poly1305 transport encryption
 * as documented in `docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md` §3.
 */
public interface DaveSessionHandle {
    /** Hot stream of session state, used by the voice client to surface DAVE UI status. */
    public val state: StateFlow<DaveSessionState>

    /**
     * Initialize the underlying MLS identity, generate a KeyPackage, and announce on
     * the voice gateway. Idempotent across reconnects; safe to invoke once per
     * call session.
     */
    public suspend fun init()

    /** Build a per-SSRC outbound frame encryptor, or null when DAVE is unavailable. */
    public suspend fun frameEncryptor(ssrc: Int): FrameEncryptor?

    /** Build a per-(senderUserId, SSRC) inbound frame decryptor, or null when DAVE is unavailable. */
    public suspend fun frameDecryptor(userId: String, ssrc: Int): FrameDecryptor?

    /** Route a binary opcode received from the voice gateway. */
    public suspend fun handleBinaryOp(op: Int, payload: ByteArray)

    /** Route a JSON opcode received from the voice gateway. */
    public suspend fun handleJsonOp(op: Int, body: JsonElement)

    /**
     * Compute the DAVE pairwise fingerprint with [remoteUserId] for SAS display.
     * Returns raw bytes (caller formats via SasFormatter). Null when unavailable.
     */
    public suspend fun pairwiseFingerprint(remoteUserId: String): ByteArray?

    /** Release native resources. Idempotent. */
    public fun close()
}

/**
 * Constructs a [DaveSessionHandle] for the active voice call.
 *
 * Concrete factory: `dev.puklic.voice.dave.LibdaveDaveSessionFactory` in
 * `:shared:voice/jvmMain` (wraps `dev.puklic.voice.dave.DaveSession` from
 * `:shared:voice-dave`). App Store wiring layers (`:ios:app`, `:desktop:app` macAppStore)
 * pass null factories to [dev.puklic.voice.DefaultVoiceClient] — voice still works,
 * just without end-to-end key agreement (Discord transport AEAD remains).
 */
public interface DaveSessionFactory {
    /**
     * Build a session bound to the given voice call.
     *
     * @param channelId Discord voice channel id (used as the MLS group id).
     * @param userId local user id (binds the MLS identity).
     * @param sendBinary callback to write a binary DAVE frame on the voice gateway
     *   (opcode + payload). The callback wraps the payload in the DAVE binary
     *   framing (seq + op) — see `DaveBinaryFrame.write`.
     * @param sendJson callback to send a JSON DAVE opcode on the voice gateway.
     */
    public fun create(
        channelId: String,
        userId: String,
        sendBinary: suspend (op: Int, payload: ByteArray) -> Unit,
        sendJson: suspend (op: Int, body: JsonElement) -> Unit,
    ): DaveSessionHandle
}
