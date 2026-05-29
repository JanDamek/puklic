package dev.puklic.desktop.macappstore.codec

import dev.puklic.voice.codec.OpusDecoder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for the Mac App Store variant's JNA-backed libopus decoder.
 *
 * FP-14b RED phase: impl class does not exist yet — FP-14c lands it.
 * ClassNotFoundException is the locked red-phase signal.
 *
 * Surface locked: class implements [OpusDecoder] from `:shared:voice-codec`
 * commonMain.
 */
class JnaLibopusDecoderContractTest {

    private val decoderFqn = "dev.puklic.desktop.macappstore.codec.JnaLibopusDecoder"

    @Test
    fun `JnaLibopusDecoder class is loadable on macAppStore classpath`() {
        val cls = Class.forName(decoderFqn)
        assertTrue(
            OpusDecoder::class.java.isAssignableFrom(cls),
            "$decoderFqn must implement OpusDecoder",
        )
    }
}
