package dev.puklic.voice.crypto

import java.util.concurrent.atomic.AtomicInteger

/**
 * Monotonic 32-bit counter used as the prefix of the 24-byte XChaCha20 nonce
 * (the remaining 20 bytes are zero per Discord's `_rtpsize` scheme).
 *
 * Wrap at 2^32 forces a session-level disconnect (~2.7 years at 50 pps), see §7.
 */
internal class NonceGenerator(initial: Int = 0) {

    private val counter = AtomicInteger(initial)

    /** Returns the next counter value (post-increment is exposed; first call yields [initial]). */
    fun next(): Int = counter.getAndIncrement()

    /** Current counter without advancing. */
    fun current(): Int = counter.get()
}
