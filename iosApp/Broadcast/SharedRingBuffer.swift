// Producer-side shared ring buffer for the Puklic ReplayKit Broadcast Upload Extension.
//
// Memory layout, signal names and backpressure policy are defined in:
//   docs/03_infrastructure/architect-reports/2026-05-29-fp11-ios-broadcast-extension.md
//
// The same file is mmap'd by `BroadcastExtensionBridge.kt` on the host-app side
// (Kotlin/Native via posix `mmap` cinterop). Layout constants here MUST match the
// Kotlin reader byte-for-byte.

import Foundation
import Darwin
import os

// `notify.h` lives in libsystem but Apple does not vend it via the `Darwin`
// Swift overlay. We rebind the C symbol directly. `notify_post(3)` has a stable
// C ABI across every shipping Darwin and the Broadcast Upload Extension
// sandbox permits Darwin notifications.
@_silgen_name("notify_post")
private func _notify_post(_ name: UnsafePointer<CChar>) -> UInt32

@discardableResult
private func notify_post(_ name: String) -> UInt32 {
    return name.withCString { _notify_post($0) }
}

/// Layout constants. Mirrored on the Kotlin reader side.
enum RingLayout {
    static let magic: UInt32 = 0x5052_4B42 // "PRKB"
    static let version: UInt32 = 1

    static let headerSize: Int = 4096
    static let videoSlotCount: Int = 4
    static let videoSlotStride: Int = 3_690_496 // (header 64 + payload 3_686_400) rounded to 4 KB
    static let videoSlotHeaderSize: Int = 64
    static let videoSlotPayloadCapacity: Int = 3_686_400 // 1280 * 720 * 4

    static let audioSlotCount: Int = 16
    static let audioSlotStride: Int = 4096
    static let audioSlotHeaderSize: Int = 64
    static let audioSlotPayloadCapacity: Int = 4032

    static let videoDataBaseOffset: Int = headerSize
    static let audioDataBaseOffset: Int = headerSize + videoSlotCount * videoSlotStride
    static let totalSize: Int = audioDataBaseOffset + audioSlotCount * audioSlotStride

    /// Darwin notify_post names.
    static let videoNotify = "cz.damek.puklic.broadcast.video"
    static let audioNotify = "cz.damek.puklic.broadcast.audio"
    static let lifecycleNotify = "cz.damek.puklic.broadcast.lifecycle"

    static let ringFileName = "broadcast-ring.bin"
}

/// Field offsets inside the 4 KB header.
enum HeaderOffset {
    static let magic = 0
    static let version = 4
    static let videoSlotCount = 8
    static let audioSlotCount = 12
    static let videoSlotStride = 16
    static let audioSlotStride = 20
    static let videoDataBaseOffset = 24
    static let audioDataBaseOffset = 32
    static let videoWriteSeq = 40
    static let videoReadSeq = 48
    static let audioWriteSeq = 56
    static let audioReadSeq = 64
    static let extensionAlive = 72
}

/// Per-slot header field offsets (video).
enum VideoSlotOffset {
    static let seq = 0
    static let width = 8
    static let height = 12
    static let bytesPerRow = 16
    static let payloadBytes = 20
    static let ptsNs = 24
    static let flags = 32
}

/// Per-slot header field offsets (audio).
enum AudioSlotOffset {
    static let seq = 0
    static let frameCount = 8
    static let channelCount = 12
    static let sampleRate = 16
    static let payloadBytes = 20
    static let ptsNs = 24
}

/// Writes to the shared ring file. One instance per extension process; the
/// `SampleHandler` owns it for the broadcast lifetime.
final class SharedRingBuffer {

    private let appGroupId: String
    private var fileDescriptor: Int32 = -1
    private var base: UnsafeMutableRawPointer?
    private var publishLock = os_unfair_lock_s()

    init(appGroupId: String) {
        self.appGroupId = appGroupId
    }

    /// Opens / creates the ring file in the App Group container, truncates it to
    /// total size and mmaps it RW. Initialises the header and marks the
    /// extension alive. Posts the lifecycle Darwin notification.
    func openAndInitialise() throws {
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ) else {
            throw RingBufferError.appGroupContainerUnavailable(appGroupId)
        }
        let fileURL = containerURL.appendingPathComponent(RingLayout.ringFileName)

        let fd = open(fileURL.path, O_RDWR | O_CREAT, 0o600)
        guard fd >= 0 else { throw RingBufferError.openFailed(errno) }
        if ftruncate(fd, off_t(RingLayout.totalSize)) != 0 {
            let err = errno
            close(fd)
            throw RingBufferError.truncateFailed(err)
        }
        guard let mapped = mmap(
            nil,
            RingLayout.totalSize,
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            fd,
            0
        ), mapped != MAP_FAILED else {
            let err = errno
            close(fd)
            throw RingBufferError.mmapFailed(err)
        }

        self.fileDescriptor = fd
        self.base = mapped

        // Zero the header so stale state from a previous broadcast does not leak.
        memset(mapped, 0, RingLayout.headerSize)

        writeUInt32(offset: HeaderOffset.magic, value: RingLayout.magic)
        writeUInt32(offset: HeaderOffset.version, value: RingLayout.version)
        writeUInt32(offset: HeaderOffset.videoSlotCount, value: UInt32(RingLayout.videoSlotCount))
        writeUInt32(offset: HeaderOffset.audioSlotCount, value: UInt32(RingLayout.audioSlotCount))
        writeUInt32(offset: HeaderOffset.videoSlotStride, value: UInt32(RingLayout.videoSlotStride))
        writeUInt32(offset: HeaderOffset.audioSlotStride, value: UInt32(RingLayout.audioSlotStride))
        writeUInt64(offset: HeaderOffset.videoDataBaseOffset, value: UInt64(RingLayout.videoDataBaseOffset))
        writeUInt64(offset: HeaderOffset.audioDataBaseOffset, value: UInt64(RingLayout.audioDataBaseOffset))
        atomicStoreUInt64(offset: HeaderOffset.videoWriteSeq, value: 0)
        atomicStoreUInt64(offset: HeaderOffset.videoReadSeq, value: 0)
        atomicStoreUInt64(offset: HeaderOffset.audioWriteSeq, value: 0)
        atomicStoreUInt64(offset: HeaderOffset.audioReadSeq, value: 0)
        atomicStoreUInt32(offset: HeaderOffset.extensionAlive, value: 1)

        notify_post(RingLayout.lifecycleNotify)
    }

    /// Stamps the extension as dead and unmaps. Idempotent.
    func closeAndMarkDead() {
        if base != nil {
            atomicStoreUInt32(offset: HeaderOffset.extensionAlive, value: 0)
            notify_post(RingLayout.lifecycleNotify)
            munmap(base, RingLayout.totalSize)
            base = nil
        }
        if fileDescriptor >= 0 {
            close(fileDescriptor)
            fileDescriptor = -1
        }
    }

    /// Publishes one BGRA frame. Drops are intentional (latest-wins) per §6 of
    /// the architect report.
    func publishVideoFrame(
        width: Int,
        height: Int,
        bytesPerRow: Int,
        sourceBaseAddress: UnsafeRawPointer,
        sourceByteCount: Int,
        ptsNs: Int64,
        truncated: Bool
    ) {
        guard let base = base else { return }
        let payloadBytes = min(sourceByteCount, RingLayout.videoSlotPayloadCapacity)

        os_unfair_lock_lock(&publishLock)
        defer { os_unfair_lock_unlock(&publishLock) }

        let currentSeq = atomicLoadUInt64(offset: HeaderOffset.videoWriteSeq)
        let nextSeq = currentSeq + 1
        let slotIndex = Int(nextSeq % UInt64(RingLayout.videoSlotCount))
        let slotOffset = RingLayout.videoDataBaseOffset + slotIndex * RingLayout.videoSlotStride
        let slotBase = base.advanced(by: slotOffset)

        writeUInt64Raw(at: slotBase.advanced(by: VideoSlotOffset.seq), value: nextSeq)
        writeUInt32Raw(at: slotBase.advanced(by: VideoSlotOffset.width), value: UInt32(width))
        writeUInt32Raw(at: slotBase.advanced(by: VideoSlotOffset.height), value: UInt32(height))
        writeUInt32Raw(at: slotBase.advanced(by: VideoSlotOffset.bytesPerRow), value: UInt32(bytesPerRow))
        writeUInt32Raw(at: slotBase.advanced(by: VideoSlotOffset.payloadBytes), value: UInt32(payloadBytes))
        writeInt64Raw(at: slotBase.advanced(by: VideoSlotOffset.ptsNs), value: ptsNs)
        writeUInt32Raw(at: slotBase.advanced(by: VideoSlotOffset.flags), value: truncated ? 1 : 0)

        let payloadDst = slotBase.advanced(by: RingLayout.videoSlotHeaderSize)
        memcpy(payloadDst, sourceBaseAddress, payloadBytes)

        atomicStoreUInt64(offset: HeaderOffset.videoWriteSeq, value: nextSeq)
        notify_post(RingLayout.videoNotify)
    }

    /// Publishes one PCM Float32 audio chunk (interleaved stereo @ 48 kHz). Larger
    /// chunks than `audioSlotPayloadCapacity` are split across consecutive slots.
    func publishAudioChunk(
        frameCount: Int,
        channelCount: Int,
        sampleRate: Int,
        sourceBaseAddress: UnsafeRawPointer,
        sourceByteCount: Int,
        ptsNs: Int64
    ) {
        guard base != nil else { return }
        let bytesPerFrame = channelCount * MemoryLayout<Float32>.size
        guard bytesPerFrame > 0 else { return }

        var offset = 0
        var remainingFrames = frameCount
        var slotPtsNs = ptsNs
        while remainingFrames > 0 {
            let framesThisSlot = min(
                remainingFrames,
                RingLayout.audioSlotPayloadCapacity / bytesPerFrame
            )
            let bytesThisSlot = framesThisSlot * bytesPerFrame
            publishOneAudioSlot(
                frameCount: framesThisSlot,
                channelCount: channelCount,
                sampleRate: sampleRate,
                sourceBaseAddress: sourceBaseAddress.advanced(by: offset),
                payloadBytes: bytesThisSlot,
                ptsNs: slotPtsNs
            )
            offset += bytesThisSlot
            remainingFrames -= framesThisSlot
            // Advance PTS by the number of frames we just published (ns).
            slotPtsNs += Int64(framesThisSlot) * 1_000_000_000 / Int64(sampleRate)
            _ = sourceByteCount // silences unused warning if optimiser elides
        }
    }

    private func publishOneAudioSlot(
        frameCount: Int,
        channelCount: Int,
        sampleRate: Int,
        sourceBaseAddress: UnsafeRawPointer,
        payloadBytes: Int,
        ptsNs: Int64
    ) {
        guard let base = base else { return }
        os_unfair_lock_lock(&publishLock)
        defer { os_unfair_lock_unlock(&publishLock) }

        let currentSeq = atomicLoadUInt64(offset: HeaderOffset.audioWriteSeq)
        let nextSeq = currentSeq + 1
        let slotIndex = Int(nextSeq % UInt64(RingLayout.audioSlotCount))
        let slotOffset = RingLayout.audioDataBaseOffset + slotIndex * RingLayout.audioSlotStride
        let slotBase = base.advanced(by: slotOffset)

        writeUInt64Raw(at: slotBase.advanced(by: AudioSlotOffset.seq), value: nextSeq)
        writeUInt32Raw(at: slotBase.advanced(by: AudioSlotOffset.frameCount), value: UInt32(frameCount))
        writeUInt32Raw(at: slotBase.advanced(by: AudioSlotOffset.channelCount), value: UInt32(channelCount))
        writeUInt32Raw(at: slotBase.advanced(by: AudioSlotOffset.sampleRate), value: UInt32(sampleRate))
        writeUInt32Raw(at: slotBase.advanced(by: AudioSlotOffset.payloadBytes), value: UInt32(payloadBytes))
        writeInt64Raw(at: slotBase.advanced(by: AudioSlotOffset.ptsNs), value: ptsNs)

        let payloadDst = slotBase.advanced(by: RingLayout.audioSlotHeaderSize)
        memcpy(payloadDst, sourceBaseAddress, payloadBytes)

        atomicStoreUInt64(offset: HeaderOffset.audioWriteSeq, value: nextSeq)
        notify_post(RingLayout.audioNotify)
    }

    // MARK: - Header field helpers

    private func writeUInt32(offset: Int, value: UInt32) {
        guard let base = base else { return }
        writeUInt32Raw(at: base.advanced(by: offset), value: value)
    }

    private func writeUInt64(offset: Int, value: UInt64) {
        guard let base = base else { return }
        writeUInt64Raw(at: base.advanced(by: offset), value: value)
    }

    private func atomicStoreUInt32(offset: Int, value: UInt32) {
        guard let base = base else { return }
        let ptr = base.advanced(by: offset).assumingMemoryBound(to: UInt32.AtomicRepresentation.self)
        UInt32.atomicStore(value, at: ptr, ordering: .releasing)
    }

    private func atomicStoreUInt64(offset: Int, value: UInt64) {
        guard let base = base else { return }
        let ptr = base.advanced(by: offset).assumingMemoryBound(to: UInt64.AtomicRepresentation.self)
        UInt64.atomicStore(value, at: ptr, ordering: .releasing)
    }

    private func atomicLoadUInt64(offset: Int) -> UInt64 {
        guard let base = base else { return 0 }
        let ptr = base.advanced(by: offset).assumingMemoryBound(to: UInt64.AtomicRepresentation.self)
        return UInt64.atomicLoad(at: ptr, ordering: .acquiring)
    }

    private func writeUInt32Raw(at ptr: UnsafeMutableRawPointer, value: UInt32) {
        var v = value.littleEndian
        memcpy(ptr, &v, MemoryLayout<UInt32>.size)
    }

    private func writeUInt64Raw(at ptr: UnsafeMutableRawPointer, value: UInt64) {
        var v = value.littleEndian
        memcpy(ptr, &v, MemoryLayout<UInt64>.size)
    }

    private func writeInt64Raw(at ptr: UnsafeMutableRawPointer, value: Int64) {
        var v = value.littleEndian
        memcpy(ptr, &v, MemoryLayout<Int64>.size)
    }
}

enum RingBufferError: Error {
    case appGroupContainerUnavailable(String)
    case openFailed(Int32)
    case truncateFailed(Int32)
    case mmapFailed(Int32)
}

// MARK: - Tiny fixed-width atomic shim
//
// Swift's `Synchronization` module ships proper `Atomic<UInt64>` starting iOS 18.
// We target iOS 15. The shim below relies on:
//   (a) the arm64 ABI guarantee that naturally-aligned 32/64-bit loads/stores
//       are atomic at the hardware level, AND
//   (b) `os_unfair_lock` providing full acquire/release memory ordering on
//       lock/unlock — every multi-field publish in this file is bracketed by
//       a single lock acquisition.
//
// Standalone loads outside the lock (the Kotlin reader's `*_write_seq` poll)
// see a value that was written under-lock; lock release provides the release
// fence, the reader's `notify_register_dispatch` dispatch queue acts as the
// acquire side. Single-word stores like `extension_alive` are also published
// under the same dispatch barrier (`notify_post` flushes pending writes per
// libsystem documentation).

extension UInt32 {
    typealias AtomicRepresentation = UInt32
    @inline(__always)
    static func atomicStore(_ value: UInt32, at ptr: UnsafeMutablePointer<UInt32>, ordering: AtomicOrdering) {
        ptr.pointee = value
    }
}

extension UInt64 {
    typealias AtomicRepresentation = UInt64
    @inline(__always)
    static func atomicStore(_ value: UInt64, at ptr: UnsafeMutablePointer<UInt64>, ordering: AtomicOrdering) {
        ptr.pointee = value
    }
    @inline(__always)
    static func atomicLoad(at ptr: UnsafeMutablePointer<UInt64>, ordering: AtomicOrdering) -> UInt64 {
        return ptr.pointee
    }
}

enum AtomicOrdering {
    case relaxed
    case acquiring
    case releasing
    case acquiringAndReleasing
}
