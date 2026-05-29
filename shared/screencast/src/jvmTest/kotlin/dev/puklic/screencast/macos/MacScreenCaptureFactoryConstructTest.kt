package dev.puklic.screencast.macos

import dev.puklic.screencast.ScreenCaptureFactory
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Compile-time + classloader smoke test for the FP-8 macOS bridge surface.
 *
 * The bridge resolves Objective-C symbols lazily, so this test runs on any
 * host (Linux CI included): merely referencing
 * [MacScreenCaptureFactory] / [LibavPushH264Encoder] forces them to load,
 * which is enough to catch missing imports, broken `expect`/`actual` mappings,
 * or javacpp constant lookup failures.
 *
 * Full runtime exercise requires a Mac host with the Screen Recording TCC
 * permission granted; that path is left to manual smoke tests per the
 * architect report §4.
 */
class MacScreenCaptureFactoryConstructTest {

    @Test
    fun macScreenCaptureFactoryIsAScreenCaptureFactory() {
        val factory: ScreenCaptureFactory = MacScreenCaptureFactory
        factory.shouldBeInstanceOf<ScreenCaptureFactory>()
    }

    @Test
    fun defaultEncoderFactoryReturnsCallableLambda() {
        // We only inspect the type — invoking it would require libx264 native
        // bytes which JavaCPP extracts on first use; that's covered by other
        // jvmTest cases that already trigger libavcodec.
        val factory = MacScreenCaptureFactory.defaultH264EncoderFactory()
        factory.shouldBeInstanceOf<Function0<*>>()
    }
}
