package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.DriverFactory

/**
 * iOS [DriverFactory] backed by `app.cash.sqldelight:native-driver` (`NativeSqliteDriver`).
 *
 * Storage location: NativeSqliteDriver places the SQLite file in the iOS app sandbox
 * Application Support directory (resolved by `co.touchlab.sqliter` internally), which matches
 * [`IosPlatformPaths.databaseFile()`] — both point to `<Application Support>/puklic.db`.
 *
 * Pragmas mirror [JvmDriverFactory]:
 * - `journal_mode=WAL` for concurrent reads during writes
 * - `synchronous=NORMAL` (WAL-safe + fast)
 * - `foreign_keys=ON` enforced at every connection
 * - `temp_store=MEMORY`, `mmap_size=256 MB`, `cache_size=8 MB`
 *
 * Additive schema (e.g. `user_preferences`) is created idempotently after driver construction —
 * SQLDelight only runs `Schema.create` for the bundled SQL files, so newer additive tables that
 * exist outside the .sq files need explicit `CREATE TABLE IF NOT EXISTS`.
 */
public class IosDriverFactory(private val dbName: String = "puklic.db") : DriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(PuklicDatabase.Schema, dbName)
        applyPragmas(driver)
        ensureAdditiveTables(driver)
        return driver
    }

    private fun applyPragmas(driver: SqlDriver) {
        driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        driver.execute(null, "PRAGMA temp_store = MEMORY", 0)
        driver.execute(null, "PRAGMA mmap_size = 268435456", 0)
        driver.execute(null, "PRAGMA cache_size = -8000", 0)
    }

    private fun ensureAdditiveTables(driver: SqlDriver) {
        driver.execute(
            null,
            "CREATE TABLE IF NOT EXISTS user_preferences (" +
                "key TEXT NOT NULL PRIMARY KEY, " +
                "value TEXT NOT NULL" +
                ")",
            0,
        )
    }
}
