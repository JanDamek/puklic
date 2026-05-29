/*
 * Host-app side reader for the Puklic ReplayKit Broadcast Upload Extension.
 *
 * The extension (`iosApp/Broadcast/SampleHandler.swift`) writes BGRA video frames
 * and Float32 PCM audio chunks into a memory-mapped ring file inside the App
 * Group container and fires Darwin `notify_post` after every slot publish. This
 * file mmaps the same file from the main app process and exposes cold `Flow`s
 * the FP-12 `IosScreenCapture` actual will plug into the existing
 * `ScreenCapture.frames: Flow<EncodedFrame>` contract.
 *
 * Memory layout, signal names and backpressure semantics are documented in:
 *   docs/03_infrastructure/architect-reports/2026-05-29-fp11-ios-broadcast-extension.md
 *
 * The byte offsets and field widths in [RingLayout] MUST stay in lock-step with
 * `iosApp/Broadcast/SharedRingBuffer.swift` — drift surfaces immediately as a
 * magic-mismatch error rather than corrupted frames.
 */

package dev.puklic.screencast.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.darwin.NOTIFY_STATUS_OK
import platform.darwin.dispatch_get_global_queue
import platform.darwin.notify_cancel
import platform.darwin.notify_post
import platform.darwin.notify_register_dispatch
import platform.posix.MAP_FAILED
import platform.posix.MAP_SHARED
import platform.posix.O_RDWR
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.close
import platform.posix.memcpy
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open

/** Layout constants — MUST match `SharedRingBuffer.swift`. */
internal object RingLayout {
    const val MAGIC: UInt = 0x5052_4B42u // "PRKB"
    const val VERSION: UInt = 1u

    const val HEADER_SIZE: Int = 4096
    const val VIDEO_SLOT_COUNT: Int = 4
    const val VIDEO_SLOT_STRIDE: Int = 3_690_496
    const val VIDEO_SLOT_HEADER_SIZE: Int = 64
    const val VIDEO_SLOT_PAYLOAD_CAPACITY: Int = 3_686_400

    const val AUDIO_SLOT_COUNT: Int = 16
    const val AUDIO_SLOT_STRIDE: Int = 4096
    const val AUDIO_SLOT_HEADER_SIZE: Int = 64

    const val VIDEO_DATA_BASE_OFFSET: Int = HEADER_SIZE
    const val AUDIO_DATA_BASE_OFFSET: Int = HEADER_SIZE + VIDEO_SLOT_COUNT * VIDEO_SLOT_STRIDE
    const val TOTAL_SIZE: Int = AUDIO_DATA_BASE_OFFSET + AUDIO_SLOT_COUNT * AUDIO_SLOT_STRIDE

    const val VIDEO_NOTIFY: String = "cz.damek.puklic.broadcast.video"
    const val AUDIO_NOTIFY: String = "cz.damek.puklic.broadcast.audio"
    const val LIFECYCLE_NOTIFY: String = "cz.damek.puklic.broadcast.lifecycle"

    const val RING_FILE_NAME: String = "broadcast-ring.bin"
}

private object HeaderOffset {
    const val MAGIC = 0
    const val VIDEO_WRITE_SEQ = 40
    const val VIDEO_READ_SEQ = 48
    const val AUDIO_WRITE_SEQ = 56
    const val AUDIO_READ_SEQ = 64
    const val EXTENSION_ALIVE = 72
}

private object VideoSlotOffset {
    const val SEQ = 0
    const val WIDTH = 8
    const val HEIGHT = 12
    const val BYTES_PER_ROW = 16
    const val PAYLOAD_BYTES = 20
    const val PTS_NS = 24
    const val FLAGS = 32
}

private object AudioSlotOffset {
    const val SEQ = 0
    const val FRAME_COUNT = 8
    const val CHANNEL_COUNT = 12
    const val SAMPLE_RATE = 16
    const val PAYLOAD_BYTES = 20
    const val PTS_NS = 24
}

/** One published video frame from the extension. */
public data class BgraFrame(
    val width: Int,
    val height: Int,
    val bytesPerRow: Int,
    val ptsNs: Long,
    val data: ByteArray,
    /** True if the consumer skipped slots before reaching this one (overrun). */
    val droppedPredecessors: Boolean,
)

/** One published audio chunk (raw interleaved Float32 from the extension). */
public data class PcmAudioChunk(
    val frameCount: Int,
    val channelCount: Int,
    val sampleRate: Int,
    val ptsNs: Long,
    val floatPayload: ByteArray,
    val droppedPredecessors: Boolean,
)

/**
 * Reads from the App Group ring buffer produced by `SampleHandler.swift`. One
 * instance per active screen-share session. FP-12 wires this behind
 * `ScreenCapture` and consumes [videoFrames] / [audioFrames].
 */
@OptIn(ExperimentalForeignApi::class)
public class BroadcastExtensionBridge(
    private val appGroupId: String = "group.cz.damek.puklic.app",
) {

    private val lifecycleFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)

    /** Hot signal: latest known `extension_alive` flag, updated by the lifecycle notify. */
    public val extensionAlive: Flow<Boolean> = lifecycleFlow.asSharedFlow()

    public fun videoFrames(): Flow<BgraFrame> = channelFlow {
        val mapping = openMapping() ?: run {
            awaitExtensionAlive()
            openMapping() ?: return@channelFlow
        }
        var lastSeq = readUInt64(mapping.base, HeaderOffset.VIDEO_WRITE_SEQ)
        val channel = Channel<BgraFrame>(
            capacity = RingLayout.VIDEO_SLOT_COUNT,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val videoToken = registerDispatch(RingLayout.VIDEO_NOTIFY) {
            lastSeq = drainVideoSlots(mapping.base, lastSeq) { frame -> channel.trySend(frame) }
        }
        val lifecycleToken = registerDispatch(RingLayout.LIFECYCLE_NOTIFY) {
            val alive = readUInt32(mapping.base, HeaderOffset.EXTENSION_ALIVE) == 1u
            lifecycleFlow.tryEmit(alive)
            if (!alive) channel.close()
        }
        lastSeq = drainVideoSlots(mapping.base, lastSeq) { frame -> channel.trySend(frame) }
        try {
            for (frame in channel) send(frame)
        } finally {
            cancelDispatch(videoToken)
            cancelDispatch(lifecycleToken)
            mapping.close()
        }
    }

    public fun audioFrames(): Flow<PcmAudioChunk> = channelFlow {
        val mapping = openMapping() ?: run {
            awaitExtensionAlive()
            openMapping() ?: return@channelFlow
        }
        var lastSeq = readUInt64(mapping.base, HeaderOffset.AUDIO_WRITE_SEQ)
        val channel = Channel<PcmAudioChunk>(
            capacity = RingLayout.AUDIO_SLOT_COUNT,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val audioToken = registerDispatch(RingLayout.AUDIO_NOTIFY) {
            lastSeq = drainAudioSlots(mapping.base, lastSeq) { chunk -> channel.trySend(chunk) }
        }
        val lifecycleToken = registerDispatch(RingLayout.LIFECYCLE_NOTIFY) {
            val alive = readUInt32(mapping.base, HeaderOffset.EXTENSION_ALIVE) == 1u
            lifecycleFlow.tryEmit(alive)
            if (!alive) channel.close()
        }
        lastSeq = drainAudioSlots(mapping.base, lastSeq) { chunk -> channel.trySend(chunk) }
        try {
            for (chunk in channel) send(chunk)
        } finally {
            cancelDispatch(audioToken)
            cancelDispatch(lifecycleToken)
            mapping.close()
        }
    }

    /** Suspends until the extension flips `extension_alive` to true. */
    private suspend fun awaitExtensionAlive() {
        val signal = MutableSharedFlow<Boolean>(replay = 1)
        val token = registerDispatch(RingLayout.LIFECYCLE_NOTIFY) {
            // Try to open just enough of the file to read the flag.
            val mapping = openMapping() ?: return@registerDispatch
            val alive = readUInt32(mapping.base, HeaderOffset.EXTENSION_ALIVE) == 1u
            mapping.close()
            signal.tryEmit(alive)
        }
        try {
            signal.first { it }
        } finally {
            cancelDispatch(token)
        }
    }

    // ---- mmap open/close ----

    private class Mapping(val base: CPointer<ByteVar>, val fd: Int) {
        fun close() {
            munmap(base, RingLayout.TOTAL_SIZE.convert())
            close(fd)
        }
    }

    private fun openMapping(): Mapping? {
        val path = containerPath() ?: return null
        val fd = open(path, O_RDWR)
        if (fd < 0) return null
        val ptr = mmap(null, RingLayout.TOTAL_SIZE.convert(), PROT_READ or PROT_WRITE, MAP_SHARED, fd, 0)
        if (ptr == null || ptr == MAP_FAILED) {
            close(fd)
            return null
        }
        val base: CPointer<ByteVar> = ptr.reinterpret()
        val magic = readUInt32(base, HeaderOffset.MAGIC)
        if (magic != RingLayout.MAGIC) {
            munmap(ptr, RingLayout.TOTAL_SIZE.convert())
            close(fd)
            return null
        }
        return Mapping(base, fd)
    }

    private fun containerPath(): String? {
        val containerUrl: NSURL = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(appGroupId)
            ?: return null
        val dirPath = containerUrl.path ?: return null
        return "$dirPath/${RingLayout.RING_FILE_NAME}"
    }

    // ---- slot drains ----

    private inline fun drainVideoSlots(
        base: CPointer<ByteVar>,
        previousLastSeq: ULong,
        onEach: (BgraFrame) -> Unit,
    ): ULong {
        val writeSeq = readUInt64(base, HeaderOffset.VIDEO_WRITE_SEQ)
        if (writeSeq <= previousLastSeq) return previousLastSeq
        val slotCount = RingLayout.VIDEO_SLOT_COUNT.toULong()
        val firstUnread = previousLastSeq + 1u
        val droppedBefore = writeSeq > previousLastSeq + slotCount
        val startSeq = if (droppedBefore) writeSeq - slotCount + 1u else firstUnread
        var seq = startSeq
        var dropFlagForFirst = droppedBefore
        while (seq <= writeSeq) {
            val slotIdx = (seq % slotCount).toInt()
            val slotBase = base.offsetBy(RingLayout.VIDEO_DATA_BASE_OFFSET + slotIdx * RingLayout.VIDEO_SLOT_STRIDE)
            val slotSeq = readUInt64(slotBase, VideoSlotOffset.SEQ)
            if (slotSeq != seq) {
                seq++
                continue
            }
            val width = readUInt32(slotBase, VideoSlotOffset.WIDTH).toInt()
            val height = readUInt32(slotBase, VideoSlotOffset.HEIGHT).toInt()
            val bytesPerRow = readUInt32(slotBase, VideoSlotOffset.BYTES_PER_ROW).toInt()
            val payloadBytes = readUInt32(slotBase, VideoSlotOffset.PAYLOAD_BYTES).toInt()
            val ptsNs = readInt64(slotBase, VideoSlotOffset.PTS_NS)
            val flags = readUInt32(slotBase, VideoSlotOffset.FLAGS)
            if (payloadBytes in 1..RingLayout.VIDEO_SLOT_PAYLOAD_CAPACITY && (flags and 1u) == 0u) {
                val dst = ByteArray(payloadBytes)
                copyOut(slotBase.offsetBy(RingLayout.VIDEO_SLOT_HEADER_SIZE), dst, payloadBytes)
                onEach(
                    BgraFrame(
                        width = width,
                        height = height,
                        bytesPerRow = bytesPerRow,
                        ptsNs = ptsNs,
                        data = dst,
                        droppedPredecessors = dropFlagForFirst,
                    ),
                )
                dropFlagForFirst = false
            }
            seq++
        }
        writeUInt64(base, HeaderOffset.VIDEO_READ_SEQ, writeSeq)
        return writeSeq
    }

    private inline fun drainAudioSlots(
        base: CPointer<ByteVar>,
        previousLastSeq: ULong,
        onEach: (PcmAudioChunk) -> Unit,
    ): ULong {
        val writeSeq = readUInt64(base, HeaderOffset.AUDIO_WRITE_SEQ)
        if (writeSeq <= previousLastSeq) return previousLastSeq
        val slotCount = RingLayout.AUDIO_SLOT_COUNT.toULong()
        val firstUnread = previousLastSeq + 1u
        val droppedBefore = writeSeq > previousLastSeq + slotCount
        val startSeq = if (droppedBefore) writeSeq - slotCount + 1u else firstUnread
        var seq = startSeq
        var dropFlagForFirst = droppedBefore
        while (seq <= writeSeq) {
            val slotIdx = (seq % slotCount).toInt()
            val slotBase = base.offsetBy(RingLayout.AUDIO_DATA_BASE_OFFSET + slotIdx * RingLayout.AUDIO_SLOT_STRIDE)
            val slotSeq = readUInt64(slotBase, AudioSlotOffset.SEQ)
            if (slotSeq != seq) {
                seq++
                continue
            }
            val frameCount = readUInt32(slotBase, AudioSlotOffset.FRAME_COUNT).toInt()
            val channelCount = readUInt32(slotBase, AudioSlotOffset.CHANNEL_COUNT).toInt()
            val sampleRate = readUInt32(slotBase, AudioSlotOffset.SAMPLE_RATE).toInt()
            val payloadBytes = readUInt32(slotBase, AudioSlotOffset.PAYLOAD_BYTES).toInt()
            val ptsNs = readInt64(slotBase, AudioSlotOffset.PTS_NS)
            if (payloadBytes in 1..(RingLayout.AUDIO_SLOT_STRIDE - RingLayout.AUDIO_SLOT_HEADER_SIZE)) {
                val dst = ByteArray(payloadBytes)
                copyOut(slotBase.offsetBy(RingLayout.AUDIO_SLOT_HEADER_SIZE), dst, payloadBytes)
                onEach(
                    PcmAudioChunk(
                        frameCount = frameCount,
                        channelCount = channelCount,
                        sampleRate = sampleRate,
                        ptsNs = ptsNs,
                        floatPayload = dst,
                        droppedPredecessors = dropFlagForFirst,
                    ),
                )
                dropFlagForFirst = false
            }
            seq++
        }
        writeUInt64(base, HeaderOffset.AUDIO_READ_SEQ, writeSeq)
        return writeSeq
    }

    // ---- Darwin notify dispatch ----

    private fun registerDispatch(name: String, onSignal: () -> Unit): Int {
        val queue = dispatch_get_global_queue(0, 0u)
        return memScoped {
            val tokenVar = alloc<IntVar>()
            val status = notify_register_dispatch(name, tokenVar.ptr, queue) { _ ->
                onSignal()
            }
            if (status.toInt() != NOTIFY_STATUS_OK.toInt()) -1 else tokenVar.value
        }
    }

    private fun cancelDispatch(token: Int) {
        if (token >= 0) notify_cancel(token)
    }

    /** Posts a Darwin lifecycle signal manually — diagnostics aid for FP-12. */
    public fun postLifecycleSignal() {
        notify_post(RingLayout.LIFECYCLE_NOTIFY)
    }

    // ---- pointer + read/write helpers ----

    private fun CPointer<ByteVar>.offsetBy(offset: Int): CPointer<ByteVar> {
        // `NativePtr` defines `operator fun plus(offset: Long): NativePtr`; the
        // resulting raw address is wrapped back into a typed `CPointer` via
        // `interpretCPointer`. This is the canonical Kotlin/Native pattern for
        // byte-level pointer arithmetic on a mmap'd region.
        return interpretCPointer(this.rawValue + offset.toLong())!!
    }

    private fun readUInt32(base: CPointer<ByteVar>, offset: Int): UInt {
        val b0 = base[offset].toInt() and 0xFF
        val b1 = base[offset + 1].toInt() and 0xFF
        val b2 = base[offset + 2].toInt() and 0xFF
        val b3 = base[offset + 3].toInt() and 0xFF
        return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
    }

    private fun readUInt64(base: CPointer<ByteVar>, offset: Int): ULong {
        val lo = readUInt32(base, offset).toULong()
        val hi = readUInt32(base, offset + 4).toULong()
        return lo or (hi shl 32)
    }

    private fun readInt64(base: CPointer<ByteVar>, offset: Int): Long = readUInt64(base, offset).toLong()

    private fun writeUInt64(base: CPointer<ByteVar>, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            base[offset + i] = ((value shr (i * 8)) and 0xFFu).toByte()
        }
    }

    private fun copyOut(src: CPointer<ByteVar>, dst: ByteArray, byteCount: Int) {
        if (byteCount <= 0) return
        dst.usePinned { pinned ->
            memcpy(pinned.addressOf(0), src, byteCount.convert())
        }
    }
}
