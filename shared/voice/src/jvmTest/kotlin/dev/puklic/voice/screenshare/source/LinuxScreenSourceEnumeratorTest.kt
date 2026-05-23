package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxScreenSourceEnumeratorTest {

    @Test
    fun `returns single synthetic portal entry`() = runTest {
        val sources = LinuxScreenSourceEnumerator().list()
        assertEquals(1, sources.size)
        val first = sources.first()
        assertIs<ScreenSource.Monitor>(first)
        assertEquals(LinuxScreenSourceEnumerator.PORTAL_PICKER_ID, first.id)
        assertTrue(first.displayName.contains("portal", ignoreCase = true))
    }
}
