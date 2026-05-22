package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import dev.puklic.persistence.DriverFactory

/**
 * Android driver implementation — Phase 2 placeholder. The real implementation will use
 * `AndroidSqliteDriver(PuklicDatabase.Schema, context, "puklic.db")` and apply the same
 * PRAGMAs as [JvmDriverFactory]. Kept as a stub so the module compiles on the Android target
 * without pulling in the runtime work required for a proper implementation.
 */
class AndroidDriverFactory : DriverFactory {
    override fun createDriver(): SqlDriver =
        throw NotImplementedError("AndroidDriverFactory.createDriver() — implemented in Phase 2")
}
