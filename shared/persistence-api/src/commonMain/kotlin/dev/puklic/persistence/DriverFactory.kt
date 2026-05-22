package dev.puklic.persistence

import app.cash.sqldelight.db.SqlDriver

/**
 * Per-platform SQLDelight [SqlDriver] factory.
 *
 * Implemented as an interface (not expect/actual) per ADR-0006 / BLOCKER-1: each platform
 * provides its own implementation in [:shared:persistence-sqldelight] and wires it via DI
 * (Koin) rather than via Kotlin's expect/actual mechanism. This keeps :shared:persistence-api
 * free of platform-specific code and allows downstream modules to inject a fake driver in tests.
 */
interface DriverFactory {
    fun createDriver(): SqlDriver
}
