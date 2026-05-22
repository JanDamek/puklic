package dev.puklic.protocol.discord

/**
 * Discord user-account capability flag sent in IDENTIFY.
 *
 * Modernized to match Acheron's CURRENT_CAPABILITIES bitmask (2026-05). Critical bits:
 *   - PRIORITIZED_READY_PAYLOAD (1 << 5)        → full guild data inline in READY
 *   - PASSIVE_GUILD_UPDATE_V2   (1 << 14)       → required for Op 37 BULK subscribe semantics
 *   - CLIENT_STATE_V2            (1 << 10)
 *   - LAZY_USER_NOTES, VERSIONED_*_STATES, DEDUPE_USER_OBJECTS, etc.
 *
 * Value = 1 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 512 | 1024 | 4096 | 8192 | 16384
 *       | (1<<17) | (1<<19) | (1<<20)
 */
internal object Capabilities {
    const val CAPABILITIES_VERSION: Int = 1_734_653
}
