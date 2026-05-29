// ReplayKit Broadcast Upload Extension principal class for Puklic.
//
// Architecture: see
//   docs/03_infrastructure/architect-reports/2026-05-29-fp11-ios-broadcast-extension.md
//
// The extension runs in its own sandboxed process (≤ 50 MB RAM cap). System
// ReplayKit delivers `CMSampleBuffer`s on its own thread. We copy each video
// frame's BGRA plane and each audio chunk into a shared mmap'd ring buffer in
// the App Group container, then signal the host app via Darwin `notify_post`.
//
// The host-side reader (Kotlin) is `BroadcastExtensionBridge.kt` in
// `:shared:screencast` iosMain. FP-12 will wrap an `IosScreenCapture :
// ScreenCapture` on top of it.

import ReplayKit
import CoreMedia
import CoreVideo
import AVFoundation

/// App Group identifier used by both targets. Kept here as a literal because
/// the entitlements file is also a literal — drift would surface as a runtime
/// container-URL nil, which `openAndInitialise()` raises immediately.
private let appGroupIdentifier = "group.cz.damek.puklic.app"

final class SampleHandler: RPBroadcastSampleHandler {

    private let ring = SharedRingBuffer(appGroupId: appGroupIdentifier)
    private var broadcastStartHostNs: Int64 = 0

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        broadcastStartHostNs = nowMonotonicNs()
        do {
            try ring.openAndInitialise()
        } catch {
            // ReplayKit's contract: call finishBroadcastWithError on fatal
            // initialisation failure. The error surfaces in the system picker
            // UI so the user knows something went wrong (rather than the
            // extension silently producing no frames).
            let nsError = NSError(
                domain: "cz.damek.puklic.broadcast",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Shared ring buffer init failed: \(error)"]
            )
            finishBroadcastWithError(nsError)
        }
    }

    override func broadcastPaused() {
        // ReplayKit stops delivering buffers; nothing to do. The ring buffer
        // stays mmap'd; resume reuses it.
    }

    override func broadcastResumed() {
        // Symmetric no-op; documented in the architect report §4.
    }

    override func broadcastFinished() {
        ring.closeAndMarkDead()
    }

    override func processSampleBuffer(
        _ sampleBuffer: CMSampleBuffer,
        with sampleBufferType: RPSampleBufferType
    ) {
        switch sampleBufferType {
        case .video:
            handleVideo(sampleBuffer)
        case .audioApp:
            handleAudio(sampleBuffer)
        case .audioMic:
            // Mic audio rides on the regular Discord voice channel via the
            // main app's voice client. The screen-share extension intentionally
            // ignores it — see architect report §4.
            break
        @unknown default:
            break
        }
    }

    // MARK: - Video

    private func handleVideo(_ sampleBuffer: CMSampleBuffer) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }

        let byteCount = bytesPerRow * height
        let capacity = RingLayout.videoSlotPayloadCapacity
        let truncated = byteCount > capacity
        let publishBytes = min(byteCount, capacity)

        let pts = sampleBufferPtsNs(sampleBuffer)
        ring.publishVideoFrame(
            width: width,
            height: height,
            bytesPerRow: bytesPerRow,
            sourceBaseAddress: UnsafeRawPointer(base),
            sourceByteCount: publishBytes,
            ptsNs: pts,
            truncated: truncated
        )
    }

    // MARK: - Audio

    private func handleAudio(_ sampleBuffer: CMSampleBuffer) {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
        else { return }
        let asbd = asbdPtr.pointee

        // We expect interleaved Float32 from ReplayKit's audioApp; if a future
        // OS delivers a different format, we still publish the bytes raw and
        // FP-12 / the Opus encoder is responsible for the format conversion.
        let channelCount = Int(asbd.mChannelsPerFrame)
        let sampleRate = Int(asbd.mSampleRate)
        let frameCount = Int(CMSampleBufferGetNumSamples(sampleBuffer))
        guard channelCount > 0, sampleRate > 0, frameCount > 0 else { return }

        var blockBuffer: CMBlockBuffer?
        var audioBufferList = AudioBufferList(
            mNumberBuffers: 1,
            mBuffers: AudioBuffer(mNumberChannels: 0, mDataByteSize: 0, mData: nil)
        )
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: &audioBufferList,
            bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let mData = audioBufferList.mBuffers.mData else { return }
        let byteCount = Int(audioBufferList.mBuffers.mDataByteSize)
        let pts = sampleBufferPtsNs(sampleBuffer)

        ring.publishAudioChunk(
            frameCount: frameCount,
            channelCount: channelCount,
            sampleRate: sampleRate,
            sourceBaseAddress: UnsafeRawPointer(mData),
            sourceByteCount: byteCount,
            ptsNs: pts
        )
        // blockBuffer is auto-released when it leaves scope.
        _ = blockBuffer
    }

    // MARK: - PTS

    private func sampleBufferPtsNs(_ sampleBuffer: CMSampleBuffer) -> Int64 {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if pts.flags.contains(.valid) {
            let seconds = CMTimeGetSeconds(pts)
            if seconds.isFinite {
                return Int64(seconds * 1_000_000_000)
            }
        }
        return nowMonotonicNs() - broadcastStartHostNs
    }

    private func nowMonotonicNs() -> Int64 {
        var ts = timespec()
        clock_gettime(CLOCK_MONOTONIC, &ts)
        return Int64(ts.tv_sec) * 1_000_000_000 + Int64(ts.tv_nsec)
    }
}
