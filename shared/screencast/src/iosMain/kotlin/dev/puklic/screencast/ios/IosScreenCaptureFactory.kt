package dev.puklic.screencast.ios

import dev.puklic.screencast.H264EncoderFactory
import dev.puklic.screencast.ScreenCapture
import dev.puklic.screencast.ScreenCaptureFactory
import dev.puklic.voice.screenshare.ScreenSource

/**
 * iOS [ScreenCaptureFactory] — produces an [IosScreenCapture] for the synthetic
 * [IosScreenSourceEnumerator] source.
 *
 * [source] is ignored beyond an assertion that it carries the expected id — iOS captures the
 * full device screen and the picker only confirms the start (see
 * `IosShareScreenConfirmDialog`). [shareAudio] is forwarded as-is; the iOS audio leg of the
 * pipeline is wired only when this flag is true (user-approved default ON per HARD RULE #3
 * 2026-05-29).
 */
public object IosScreenCaptureFactory : ScreenCaptureFactory {

    public override fun create(
        source: ScreenSource,
        shareAudio: Boolean,
        h264EncoderFactory: H264EncoderFactory,
    ): ScreenCapture {
        require(source.id == IosScreenSourceEnumerator.ID) {
            "IosScreenCaptureFactory expects the synthetic '${IosScreenSourceEnumerator.ID}' " +
                "source produced by IosScreenSourceEnumerator; got '${source.id}'"
        }
        return IosScreenCapture(
            shareAudio = shareAudio,
            h264EncoderFactory = h264EncoderFactory,
        )
    }
}
