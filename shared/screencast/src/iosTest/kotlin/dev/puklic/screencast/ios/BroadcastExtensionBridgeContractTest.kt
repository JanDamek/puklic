/*
 * Compile-only contract test for `BroadcastExtensionBridge`.
 *
 * The bridge mmaps an App Group container that only exists inside the iOS app
 * sandbox, so a real round-trip test would need a hosted XCTest target — out of
 * scope for FP-11 (the architect report §1 lists XCTest setup as deferred). The
 * tests below assert the bridge's public surface compiles and instantiates so
 * any drift in `BgraFrame` / `PcmAudioChunk` / signal names is caught by
 * `compileTestKotlinIos*` immediately.
 *
 * Reference:
 *   docs/03_infrastructure/architect-reports/2026-05-29-fp11-ios-broadcast-extension.md §11
 */

package dev.puklic.screencast.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BroadcastExtensionBridgeContractTest {

    @Test
    fun bridgeInstantiatesWithDefaultAppGroupId() {
        val bridge = BroadcastExtensionBridge()
        assertNotNull(bridge)
        assertNotNull(bridge.videoFrames())
        assertNotNull(bridge.audioFrames())
        assertNotNull(bridge.extensionAlive)
    }

    @Test
    fun ringLayoutConstantsMatchExtensionSwiftSide() {
        // These numbers MUST match `iosApp/Broadcast/SharedRingBuffer.swift`.
        // If a future edit drifts either side, this test breaks the build.
        assertEquals(0x5052_4B42u, RingLayout.MAGIC)
        assertEquals(1u, RingLayout.VERSION)
        assertEquals(4096, RingLayout.HEADER_SIZE)
        assertEquals(4, RingLayout.VIDEO_SLOT_COUNT)
        assertEquals(3_690_496, RingLayout.VIDEO_SLOT_STRIDE)
        assertEquals(3_686_400, RingLayout.VIDEO_SLOT_PAYLOAD_CAPACITY)
        assertEquals(16, RingLayout.AUDIO_SLOT_COUNT)
        assertEquals(4096, RingLayout.AUDIO_SLOT_STRIDE)
        assertEquals(RingLayout.HEADER_SIZE, RingLayout.VIDEO_DATA_BASE_OFFSET)
        assertEquals(
            RingLayout.HEADER_SIZE + RingLayout.VIDEO_SLOT_COUNT * RingLayout.VIDEO_SLOT_STRIDE,
            RingLayout.AUDIO_DATA_BASE_OFFSET,
        )
        // 14 MB video + 64 KB audio + 4 KB header ≪ 50 MB ReplayKit extension cap.
        assertTrue(RingLayout.TOTAL_SIZE < 20 * 1024 * 1024)
        assertEquals("cz.damek.puklic.broadcast.video", RingLayout.VIDEO_NOTIFY)
        assertEquals("cz.damek.puklic.broadcast.audio", RingLayout.AUDIO_NOTIFY)
        assertEquals("cz.damek.puklic.broadcast.lifecycle", RingLayout.LIFECYCLE_NOTIFY)
    }

    @Test
    fun frameDataClassesPreserveByteArrayPayload() {
        val payload = ByteArray(16) { it.toByte() }
        val frame = BgraFrame(
            width = 1280,
            height = 720,
            bytesPerRow = 1280 * 4,
            ptsNs = 1_000_000L,
            data = payload,
            droppedPredecessors = false,
        )
        assertEquals(16, frame.data.size)
        assertEquals(0, frame.data[0].toInt())
        assertEquals(15, frame.data[15].toInt())

        val audio = PcmAudioChunk(
            frameCount = 480,
            channelCount = 2,
            sampleRate = 48_000,
            ptsNs = 2_000_000L,
            floatPayload = payload,
            droppedPredecessors = false,
        )
        assertEquals(480, audio.frameCount)
        assertEquals(48_000, audio.sampleRate)
    }
}
