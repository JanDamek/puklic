package dev.puklic.voice.crypto

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Monotonic 32-bit counter used as the prefix of the 24-byte XChaCha20 nonce
 * (the remaining 20 bytes are zero per Discord's `_rtpsize` scheme).
 *
 * Wrap at 2^32 forces a session-level disconnect (~2.7 years at 50 pps), see
 * architect report 2026-05-23-voice.md §7.
 *
 * KMP — backed by the Kotlin stdlib KMP atomic (`kotlin.concurrent.atomics`).
 */
@OptIn(ExperimentalAtomicApi::class)
public class NonceGenerator(initial: Int = 0) {

    private val counter = AtomicInt(initial)

    /** Returns the next counter value (post-increment is exposed; first call yields `initial`). */
    public fun next(): Int = counter.fetchAndAdd(1)

    /** Current counter without advancing. */
    public fun current(): Int = counter.load()
}
