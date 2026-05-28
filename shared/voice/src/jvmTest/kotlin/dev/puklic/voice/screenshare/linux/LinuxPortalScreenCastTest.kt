package dev.puklic.voice.screenshare.linux

import dev.puklic.voice.screenshare.linux.PortalStreamKind
import dev.puklic.voice.screenshare.linux.audioNodes
import dev.puklic.voice.screenshare.linux.videoNodes
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Pure-JVM tests for the small helpers in [LinuxPortalScreenCast]. The full portal flow
 * requires a session D-Bus + xdg-desktop-portal, which is unavailable in CI; see
 * `docs/05_platforms/linux-wayland.md` for the manual smoke test.
 *
 * Integration coverage for the live D-Bus handshake is gated by Linux + DBUS_SESSION_BUS_ADDRESS;
 * see [LinuxPortalScreenCastIntegrationTest].
 */
class LinuxPortalScreenCastTest {

    @Test
    fun `dbus FileDescriptor round-trips int fd`() {
        // The portal's OpenPipeWireRemote returns an org.freedesktop.dbus.FileDescriptor;
        // its getIntFileDescriptor() is what we pass into libavdevice. Sanity check the
        // wrapper class is on the classpath and exposes the int field as expected.
        val wrapped = FileDescriptor(7)
        assertEquals(7, wrapped.intFileDescriptor)
    }

    @Test
    fun `CaptureMode masks match portal SourceType spec`() {
        assertEquals(1, LinuxPortalScreenCast.CaptureMode.Monitors.mask)
        assertEquals(2, LinuxPortalScreenCast.CaptureMode.Windows.mask)
        assertEquals(3, LinuxPortalScreenCast.CaptureMode.MonitorsAndWindows.mask)
    }

    @Test
    fun `CursorMode masks match portal CursorMode spec`() {
        assertEquals(1, LinuxPortalScreenCast.CursorMode.Hidden.mask)
        assertEquals(2, LinuxPortalScreenCast.CursorMode.Embedded.mask)
        assertEquals(4, LinuxPortalScreenCast.CursorMode.Metadata.mask)
    }

    @Test
    fun `decodeResponseCode maps 0 to Ok`() {
        assertEquals(LinuxPortalScreenCast.ResponseCode.Ok, LinuxPortalScreenCast.decodeResponseCode(0))
    }

    @Test
    fun `decodeResponseCode maps 1 to UserCancelled`() {
        assertEquals(
            LinuxPortalScreenCast.ResponseCode.UserCancelled,
            LinuxPortalScreenCast.decodeResponseCode(1),
        )
    }

    @Test
    fun `decodeResponseCode maps 2 and other to Error`() {
        assertEquals(LinuxPortalScreenCast.ResponseCode.Error, LinuxPortalScreenCast.decodeResponseCode(2))
        assertEquals(LinuxPortalScreenCast.ResponseCode.Error, LinuxPortalScreenCast.decodeResponseCode(99))
        assertEquals(LinuxPortalScreenCast.ResponseCode.Error, LinuxPortalScreenCast.decodeResponseCode(-1))
    }

    @Test
    fun `extractAllStreams classifies a single video row by size property`() {
        val sizeStruct: Array<Any?> = arrayOf(1920, 1080)
        val props: Map<String, Variant<*>> = mapOf("size" to Variant<Array<Any?>>(sizeStruct, "(ii)"))
        val row: Array<Any?> = arrayOf(UInt32(12L), props)
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(row), "a(ua{sv})"),
        )

        val streams = LinuxPortalScreenCast.extractAllStreams(results)
        assertEquals(1, streams.size)
        assertEquals(12, streams[0].nodeId)
        assertEquals(PortalStreamKind.Video, streams[0].kind)
        assertEquals(listOf(12), streams.videoNodes())
        assertEquals(emptyList(), streams.audioNodes())
    }

    @Test
    fun `extractAllStreams classifies video plus audio rows`() {
        val sizeStruct: Array<Any?> = arrayOf(1920, 1080)
        val videoProps: Map<String, Variant<*>> = mapOf("size" to Variant<Array<Any?>>(sizeStruct, "(ii)"))
        val videoRow: Array<Any?> = arrayOf(UInt32(12L), videoProps)
        // Audio sub-stream: no "size" property — heuristic flags this as Audio.
        val audioRow: List<Any?> = listOf(UInt32(13L), emptyMap<String, Variant<*>>())
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(videoRow, audioRow), "a(ua{sv})"),
        )

        val streams = LinuxPortalScreenCast.extractAllStreams(results)
        assertEquals(2, streams.size)
        assertEquals(PortalStreamKind.Video, streams[0].kind)
        assertEquals(PortalStreamKind.Audio, streams[1].kind)
        assertEquals(listOf(12), streams.videoNodes())
        assertEquals(listOf(13), streams.audioNodes())
    }

    @Test
    fun `extractAllStreams treats all rows lacking size as audio`() {
        val rowA: List<Any?> = listOf(UInt32(33L), emptyMap<String, Variant<*>>())
        val rowB: Array<Any?> = arrayOf(UInt32(44L), emptyMap<String, Variant<*>>())
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(rowA, rowB), "a(ua{sv})"),
        )

        val streams = LinuxPortalScreenCast.extractAllStreams(results)
        assertEquals(listOf(PortalStreamKind.Audio, PortalStreamKind.Audio), streams.map { it.kind })
        assertEquals(emptyList(), streams.videoNodes())
        assertEquals(listOf(33, 44), streams.audioNodes())
    }

    @Test
    fun `extractAllStreams fails when streams missing`() {
        assertFails { LinuxPortalScreenCast.extractAllStreams(emptyMap()) }
    }

    @Test
    fun `extractAllStreams returns empty list when streams empty`() {
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(emptyList<Any?>(), "a(ua{sv})"),
        )
        assertEquals(emptyList(), LinuxPortalScreenCast.extractAllStreams(results))
    }

    @Test
    fun `extractAllStreams fails on malformed row shape`() {
        // A row that is neither Array nor List — bare nodeId. extractNodeId should surface
        // an error rather than silently drop the stream (per HARD RULE #2 fail-fast).
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>("not-a-row"), "a(ua{sv})"),
        )
        assertFails { LinuxPortalScreenCast.extractAllStreams(results) }
    }
}
