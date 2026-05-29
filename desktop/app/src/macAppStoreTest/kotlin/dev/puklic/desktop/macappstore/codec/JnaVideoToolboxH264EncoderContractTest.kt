package dev.puklic.desktop.macappstore.codec

import dev.puklic.voice.codec.video.H264Encoder
import dev.puklic.voice.codec.video.H264EncoderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for the Mac App Store variant's JNA-backed VideoToolbox H.264 encoder.
 *
 * FP-14b RED phase: the impl class
 * `dev.puklic.desktop.macappstore.codec.JnaVideoToolboxH264Encoder` (and its
 * companion factory) DOES NOT EXIST yet — FP-14c will introduce it. Until then
 * `Class.forName(...)` throws ClassNotFoundException, which is the locked
 * red-phase signal.
 *
 * Surface locked here:
 *  1. Class is loadable on the macAppStoreTest classpath.
 *  2. Class implements [H264Encoder] from `:shared:voice-codec` commonMain.
 *  3. A factory object `JnaVideoToolboxH264EncoderFactory` exists and
 *     implements [H264EncoderFactory] with the
 *     (width, height, bitrateKbps, framerate) signature.
 *
 * See: docs/03_infrastructure/architect-reports/2026-05-29-fp14a-mac-app-store-architect.md §2.1 + §4.2
 */
class JnaVideoToolboxH264EncoderContractTest {

    private val encoderFqn = "dev.puklic.desktop.macappstore.codec.JnaVideoToolboxH264Encoder"
    private val factoryFqn = "dev.puklic.desktop.macappstore.codec.JnaVideoToolboxH264EncoderFactory"

    @Test
    fun `JnaVideoToolboxH264Encoder class is loadable on macAppStore classpath`() {
        val cls = Class.forName(encoderFqn)
        assertTrue(
            H264Encoder::class.java.isAssignableFrom(cls),
            "$encoderFqn must implement H264Encoder",
        )
    }

    @Test
    fun `JnaVideoToolboxH264EncoderFactory is an H264EncoderFactory singleton`() {
        val cls = Class.forName(factoryFqn)
        assertTrue(
            H264EncoderFactory::class.java.isAssignableFrom(cls),
            "$factoryFqn must implement H264EncoderFactory",
        )
    }
}
