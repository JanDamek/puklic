package dev.puklic.voice.dave

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings to Discord's libdave C API (see `cpp/includes/dave/dave.h` in
 * https://github.com/discord/libdave).
 *
 * Only the surface needed by [LibdaveMlsClient] + the future frame encryptor /
 * decryptor wiring is exposed here. We resolve symbols by name from the
 * bundled "dave" library; [LibdaveLoader.load] must have been called first.
 *
 * All `uint8_t**` out parameters are returned as `PointerByReference`; the
 * caller MUST copy bytes out and then free the libdave allocation via
 * [daveFree]. Forgetting to free leaks malloc'd memory inside the native lib.
 *
 * `size_t` is mapped to `Long` (JNA convention on 64-bit platforms). On
 * 32-bit JVMs this would need [com.sun.jna.NativeLong] — none of our targets
 * are 32-bit, so we keep the simpler typing.
 */
@Suppress("FunctionParameterNaming", "FunctionNaming", "TooManyFunctions", "LongParameterList")
internal interface LibdaveBindings : Library {

    /** Discord MLS failure callback: `(source, reason, userData)` — strings owned by libdave. */
    fun interface MlsFailureCallback : Callback {
        operator fun invoke(source: String?, reason: String?, userData: Pointer?)
    }

    /** Pairwise fingerprint callback. Bytes are owned by libdave (freed after return). */
    fun interface PairwiseFingerprintCallback : Callback {
        operator fun invoke(fingerprint: Pointer?, length: Long, userData: Pointer?)
    }

    // --- Version + memory ---
    fun daveMaxSupportedProtocolVersion(): Short
    fun daveFree(ptr: Pointer?)

    // --- Session ---
    fun daveSessionCreate(
        context: Pointer?,
        authSessionId: String?,
        callback: MlsFailureCallback?,
        userData: Pointer?,
    ): Pointer?

    fun daveSessionDestroy(session: Pointer?)

    fun daveSessionInit(session: Pointer?, version: Short, groupId: Long, selfUserId: String)

    fun daveSessionReset(session: Pointer?)

    fun daveSessionSetProtocolVersion(session: Pointer?, version: Short)
    fun daveSessionGetProtocolVersion(session: Pointer?): Short

    fun daveSessionGetLastEpochAuthenticator(
        session: Pointer?,
        outBytes: PointerByReference,
        outLength: LongArray,
    )

    fun daveSessionSetExternalSender(session: Pointer?, externalSender: ByteArray, length: Long)

    fun daveSessionProcessProposals(
        session: Pointer?,
        proposals: ByteArray,
        length: Long,
        recognizedUserIds: Array<String>?,
        recognizedUserIdsLength: Long,
        outCommitWelcome: PointerByReference,
        outLength: LongArray,
    )

    fun daveSessionProcessCommit(
        session: Pointer?,
        commit: ByteArray,
        length: Long,
    ): Pointer?

    fun daveSessionProcessWelcome(
        session: Pointer?,
        welcome: ByteArray,
        length: Long,
        recognizedUserIds: Array<String>?,
        recognizedUserIdsLength: Long,
    ): Pointer?

    fun daveSessionGetMarshalledKeyPackage(
        session: Pointer?,
        outBytes: PointerByReference,
        outLength: LongArray,
    )

    fun daveSessionGetKeyRatchet(session: Pointer?, userId: String): Pointer?

    fun daveSessionGetPairwiseFingerprint(
        session: Pointer?,
        version: Short,
        userId: String,
        callback: PairwiseFingerprintCallback,
        userData: Pointer?,
    )

    // --- Key ratchet ---
    fun daveKeyRatchetDestroy(keyRatchet: Pointer?)

    // --- Commit / Welcome result ---
    fun daveCommitResultIsFailed(handle: Pointer?): Boolean
    fun daveCommitResultIsIgnored(handle: Pointer?): Boolean
    fun daveCommitResultDestroy(handle: Pointer?)
    fun daveWelcomeResultDestroy(handle: Pointer?)

    // --- Encryptor (frame encrypt) ---
    fun daveEncryptorCreate(): Pointer?
    fun daveEncryptorDestroy(handle: Pointer?)
    fun daveEncryptorSetKeyRatchet(handle: Pointer?, keyRatchet: Pointer?)
    fun daveEncryptorSetPassthroughMode(handle: Pointer?, passthrough: Boolean)
    fun daveEncryptorAssignSsrcToCodec(handle: Pointer?, ssrc: Int, codec: Int)
    fun daveEncryptorGetProtocolVersion(handle: Pointer?): Short
    fun daveEncryptorGetMaxCiphertextByteSize(handle: Pointer?, mediaType: Int, frameSize: Long): Long
    fun daveEncryptorHasKeyRatchet(handle: Pointer?): Boolean
    fun daveEncryptorIsPassthroughMode(handle: Pointer?): Boolean
    fun daveEncryptorEncrypt(
        handle: Pointer?,
        mediaType: Int,
        ssrc: Int,
        frame: ByteArray,
        frameLength: Long,
        encryptedFrame: ByteArray,
        encryptedFrameCapacity: Long,
        bytesWritten: LongArray,
    ): Int

    // --- Decryptor (frame decrypt) ---
    fun daveDecryptorCreate(): Pointer?
    fun daveDecryptorDestroy(handle: Pointer?)
    fun daveDecryptorTransitionToKeyRatchet(handle: Pointer?, keyRatchet: Pointer?)
    fun daveDecryptorTransitionToPassthroughMode(handle: Pointer?, passthrough: Boolean)
    fun daveDecryptorDecrypt(
        handle: Pointer?,
        mediaType: Int,
        encryptedFrame: ByteArray,
        encryptedFrameLength: Long,
        frame: ByteArray,
        frameCapacity: Long,
        bytesWritten: LongArray,
    ): Int

    fun daveDecryptorGetMaxPlaintextByteSize(handle: Pointer?, mediaType: Int, encryptedFrameSize: Long): Long

    companion object {
        // Library name "dave" → resolves to libdave.dylib / libdave.so / dave.dll.
        // The System.load in LibdaveLoader makes symbols globally available; JNA's
        // Native.load still needs a name to attach a function-resolution context.
        val INSTANCE: LibdaveBindings by lazy {
            LibdaveLoader.load()
            Native.load("dave", LibdaveBindings::class.java)
        }
    }
}
