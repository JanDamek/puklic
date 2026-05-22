package dev.puklic.protocol.discord

/**
 * Discord user-account capability flag sent in IDENTIFY.
 *
 * Per `docs/02_domain/discord-protocol.md`, currently 16381 (May 2026). Discord may bump
 * this value silently. If READY/READY_SUPPLEMENTAL shape diverges, log a warning and update
 * here — see `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` §9.
 */
internal object Capabilities {
    const val CAPABILITIES_VERSION: Int = 16381
}
