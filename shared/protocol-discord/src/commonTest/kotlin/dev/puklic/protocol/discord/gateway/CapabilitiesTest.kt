package dev.puklic.protocol.discord.gateway

import dev.puklic.protocol.discord.Capabilities
import kotlin.test.Test
import kotlin.test.assertTrue

class CapabilitiesTest {
    @Test
    fun capabilities_version_is_non_zero() {
        assertTrue(Capabilities.CAPABILITIES_VERSION > 0)
    }
}
