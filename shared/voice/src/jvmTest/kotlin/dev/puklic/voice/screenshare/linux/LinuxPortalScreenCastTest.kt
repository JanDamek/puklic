package dev.puklic.voice.screenshare.linux

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
    fun `extractStreams parses array-shaped row`() {
        val nodeId = 42
        val row: Array<Any?> = arrayOf(UInt32(nodeId.toLong()), emptyMap<String, Variant<*>>())
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(row), "a(ua{sv})"),
        )
        assertEquals(nodeId, LinuxPortalScreenCast.extractStreams(results))
    }

    @Test
    fun `extractStreams parses list-shaped row`() {
        val nodeId = 7
        val row: List<Any?> = listOf(UInt32(nodeId.toLong()), emptyMap<String, Variant<*>>())
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(row), "a(ua{sv})"),
        )
        assertEquals(nodeId, LinuxPortalScreenCast.extractStreams(results))
    }

    @Test
    fun `extractAllStreams returns every node id in order`() {
        val rowA: List<Any?> = listOf(UInt32(11L), emptyMap<String, Variant<*>>())
        val rowB: Array<Any?> = arrayOf(UInt32(22L), emptyMap<String, Variant<*>>())
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(listOf<Any?>(rowA, rowB), "a(ua{sv})"),
        )
        assertEquals(listOf(11, 22), LinuxPortalScreenCast.extractAllStreams(results))
    }

    @Test
    fun `extractStreams fails when streams missing`() {
        assertFails { LinuxPortalScreenCast.extractStreams(emptyMap()) }
    }

    @Test
    fun `extractStreams fails when streams empty list`() {
        val results: Map<String, Variant<*>> = mapOf(
            "streams" to Variant<List<*>>(emptyList<Any?>(), "a(ua{sv})"),
        )
        assertFails { LinuxPortalScreenCast.extractStreams(results) }
    }
}
