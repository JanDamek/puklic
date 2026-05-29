package dev.puklic.screencast.windows

import dev.puklic.screencast.ScreenCaptureFactory
import dev.puklic.screencast.ScreenSourceEnumerator
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Compile-time + classloader smoke test for the FP-9 Windows bridge surface.
 *
 * The DXGI / D3D11 / WASAPI JNA bindings load `Dxgi.dll` / `D3d11.dll` lazily; the bridges
 * are `object`s whose `NativeLibrary.getInstance(...)` calls only happen on first method
 * invocation. Referencing [WindowsScreenCaptureFactory] / [WindowsScreenSourceEnumerator]
 * therefore links cleanly on every host, including macOS — which is what FP-9's
 * "Mac-host compile" acceptance gates on. Runtime exercise on Windows is FP-10's
 * responsibility.
 */
class WindowsScreenCaptureFactoryConstructionTest {

    @Test
    fun windowsScreenCaptureFactoryIsAScreenCaptureFactory() {
        val factory: ScreenCaptureFactory = WindowsScreenCaptureFactory
        factory.shouldBeInstanceOf<ScreenCaptureFactory>()
    }

    @Test
    fun windowsScreenSourceEnumeratorImplementsContract() {
        val enumerator: ScreenSourceEnumerator = WindowsScreenSourceEnumerator()
        enumerator.shouldBeInstanceOf<ScreenSourceEnumerator>()
    }

    @Test
    fun createSignatureMatchesScreenCaptureFactoryContract() {
        // Force the function reference to resolve — catches any signature drift between
        // the WindowsScreenCaptureFactory.create override and ScreenCaptureFactory.create
        // at compile time without needing kotlin-reflect.
        @Suppress("UnusedPrivateProperty")
        val ref: (dev.puklic.voice.screenshare.ScreenSource, Boolean, dev.puklic.screencast.H264EncoderFactory) ->
            dev.puklic.screencast.ScreenCapture = WindowsScreenCaptureFactory::create
        check(true) { "compile-time signature check on WindowsScreenCaptureFactory::create" }
    }
}
