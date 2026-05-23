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
