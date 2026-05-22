package dev.puklic.platform

/**
 * Launch-at-login / autostart toggle. Phase 2+ feature — interface declared here
 * so that consumers compile against the final shape.
 */
interface PlatformAutoStart {
    val supported: Boolean
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
}
