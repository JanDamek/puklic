package dev.puklic.voice.dave.sas

/**
 * Renders a pairwise fingerprint byte array as a Discord-spec Short
 * Authentication String (SAS) — groups of 5-decimal-digit chunks separated by
 * a single space, each chunk encoding a big-endian uint16 word.
 *
 * Used by the "Verify call" UI: users compare the displayed SAS out-of-band
 * (voice channel, secondary channel) against the other end of the call. A
 * mismatch indicates an MITM attack on the DAVE/MLS handshake.
 *
 * Format chosen to match Discord's official client behaviour as documented in
 * https://daveprotocol.com/#pairwise-fingerprints — 16-bit BE words → 5-digit
 * zero-padded decimal groups (range 00000..65535) → space-separated.
 *
 * Pure / commonMain so it can be unit-tested without JNA.
 */
public object SasFormatter {

    /**
     * Number of decimal digits per group. uint16 max = 65535 → 5 digits is
     * the minimum width that fits every value, zero-padded for visual
     * alignment.
     */
    public const val GROUP_DIGITS: Int = 5

    /** Bytes per group (one big-endian uint16 word). */
    public const val GROUP_BYTES: Int = 2

    /** Separator between groups. A single ASCII space matches Discord's rendering. */
    public const val GROUP_SEPARATOR: String = " "

    /**
     * Format [fingerprint] as space-separated 5-digit decimal groups.
     *
     * Odd-length inputs are left-padded with a leading 0 byte so the final
     * group still encodes a full uint16.
     */
    public fun format(fingerprint: ByteArray): String {
        if (fingerprint.isEmpty()) return ""
        val padded = if (fingerprint.size % GROUP_BYTES == 0) {
            fingerprint
        } else {
            ByteArray(fingerprint.size + 1).also { fingerprint.copyInto(it, destinationOffset = 1) }
        }
        val groups = ArrayList<String>(padded.size / GROUP_BYTES)
        var i = 0
        while (i < padded.size) {
            val hi = padded[i].toInt() and 0xFF
            val lo = padded[i + 1].toInt() and 0xFF
            val word = (hi shl 8) or lo
            groups += word.toString().padStart(GROUP_DIGITS, '0')
            i += GROUP_BYTES
        }
        return groups.joinToString(GROUP_SEPARATOR)
    }
}
