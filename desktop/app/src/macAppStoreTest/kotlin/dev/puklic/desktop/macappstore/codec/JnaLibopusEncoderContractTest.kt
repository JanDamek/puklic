package dev.puklic.desktop.macappstore.codec

import dev.puklic.voice.codec.OpusEncoder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for the Mac App Store variant's JNA-backed libopus encoder.
 *
 * FP-14b RED phase: the impl class
 * `dev.puklic.desktop.macappstore.codec.JnaLibopusEncoder` DOES NOT EXIST yet.
 * Loaded via `Class.forName` so the test source compiles cleanly today and
 * fails at runtime with ClassNotFoundException.
 *
 * Surface locked here:
 *  1. Class is loadable on the macAppStoreTest classpath.
 *  2. Class implements [OpusEncoder] from `:shared:voice-codec` commonMain.
 *
 * The encoder must bind to the libopus.dylib slice extracted from
 * `Opus.xcframework/macos-arm64` and bundled into the .app at
 * `Contents/Resources/Opus.framework/Opus`. See FP-14a §2.2.
 */
class JnaLibopusEncoderContractTest {

    private val encoderFqn = "dev.puklic.desktop.macappstore.codec.JnaLibopusEncoder"

    @Test
    fun `JnaLibopusEncoder class is loadable on macAppStore classpath`() {
        val cls = Class.forName(encoderFqn)
        assertTrue(
            OpusEncoder::class.java.isAssignableFrom(cls),
            "$encoderFqn must implement OpusEncoder",
        )
    }
}
