package dev.puklic.ui.components.voice

import androidx.compose.runtime.Composable
import dev.puklic.voice.IncomingVideoFrame

/**
 * Renders the latest decoded frame per remote screenshare SSRC. Hidden when [frames] is
 * empty. Each platform implements RGBA → ImageBitmap conversion using its native graphics
 * primitives:
 *  - JVM (`IncomingVideoPane.jvm.kt`) — `java.awt.image.BufferedImage` + `toComposeImageBitmap()`.
 *  - iOS (`IncomingVideoPane.ios.kt`) — `org.jetbrains.skia.Image.makeRaster(...)`.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §12 +
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp12-ios-screencast-impl.md` §2.
 */
@Composable
internal expect fun IncomingVideoPane(frames: Map<Int, IncomingVideoFrame>)
