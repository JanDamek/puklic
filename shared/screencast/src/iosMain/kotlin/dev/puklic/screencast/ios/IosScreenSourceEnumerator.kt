package dev.puklic.screencast.ios

import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.screenshare.ScreenSource

/**
 * iOS [ScreenSourceEnumerator] — ReplayKit captures the entire device screen so there is no
 * per-monitor / per-window enumeration to perform. Returns one synthetic [ScreenSource.Monitor]
 * which the user-approved confirm dialog passes through to `voiceClient.screenShare.start(...)`.
 *
 * Width/height are reported as `0`; the actual resolution is resolved at capture time by the
 * `RPBroadcastSampleHandler` extension and surfaced on each [BgraFrame] header.
 */
public object IosScreenSourceEnumerator : ScreenSourceEnumerator {

    /** Stable id used by the host UI as the dialog identity key. */
    public const val ID: String = "ios-screen"

    public override suspend fun list(): List<ScreenSource> = listOf(
        ScreenSource.Monitor(
            id = ID,
            displayName = "This iPhone",
            widthPx = 0,
            heightPx = 0,
        ),
    )
}
