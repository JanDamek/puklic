package dev.puklic.voice.screenshare.source

/**
 * Backwards-compatible alias for [dev.puklic.screencast.ScreenSourceEnumerator]
 * — kept here so the JVM dispatcher (`screenSourceEnumerator()` actual in
 * `MacScreenSourceEnumerator.jvm.kt`) and JVM tests that import
 * `dev.puklic.voice.screenshare.source.ScreenSourceEnumerator` continue to
 * compile after the FP-7 extraction.
 *
 * The canonical declaration lives in `:shared:screencast` commonMain (FP-7,
 * 2026-05-29 — see
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp7-screencast-extraction.md`).
 * When FP-8 / FP-9 / FP-12 land and rewrite `DefaultScreenShareClient` onto
 * `ScreenCaptureFactory`, this alias and the JVM `expect fun`
 * [screenSourceEnumerator] both get deleted.
 */
internal typealias ScreenSourceEnumerator = dev.puklic.screencast.ScreenSourceEnumerator

internal expect fun screenSourceEnumerator(): ScreenSourceEnumerator
