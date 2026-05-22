package dev.puklic.platform

/**
 * Phase 3 audio types — defined here so that capture/playback service signatures compile today.
 */

data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean,
)

/**
 * PCM audio frame: interleaved samples for [channels] at [sampleRateHz].
 * Sample format implied by [bitsPerSample] (16 = signed little-endian).
 */
data class AudioFrame(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            bitsPerSample == other.bitsPerSample &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        return result
    }
}
