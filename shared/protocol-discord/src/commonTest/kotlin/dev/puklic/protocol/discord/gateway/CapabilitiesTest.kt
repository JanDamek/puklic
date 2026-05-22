package dev.puklic.protocol.discord.gateway

import dev.puklic.protocol.discord.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitiesTest {
    @Test
    fun capabilities_version_matches_acheron_current_capabilities() {
        // 1 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 4096 | 8192 | 16384
        // | (1<<17) | (1<<19) | (1<<20)  = 1_734_653
        assertEquals(1_734_653, Capabilities.CAPABILITIES_VERSION)
    }

    @Test
    fun capabilities_version_is_non_zero() {
        assertTrue(Capabilities.CAPABILITIES_VERSION > 0)
    }
}
