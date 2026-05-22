package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import dev.puklic.persistence.DriverFactory

/**
 * iOS driver implementation — Phase 2/3 placeholder. The real implementation will use
 * `NativeSqliteDriver(PuklicDatabase.Schema, "puklic.db")` from `sqldelight-native-driver`
 * and apply the PRAGMAs from [JvmDriverFactory].
 */
class IosDriverFactory : DriverFactory {
    override fun createDriver(): SqlDriver =
        throw NotImplementedError("IosDriverFactory.createDriver() — implemented in Phase 2/3")
}
