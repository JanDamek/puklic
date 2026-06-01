package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
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
 * - `journal_mode=WAL` for concurrent reads during writes — set via [JournalMode.WAL] on the
 *   sqliter [DatabaseConfiguration] so it is applied during connection open (not via execute()).
 * - `foreign_keys=ON` enforced at every connection — set via
 *   [DatabaseConfiguration.Extended.foreignKeyConstraints].
 * - `synchronous=NORMAL`, `temp_store=MEMORY`, `mmap_size=256 MB`, `cache_size=8 MB` — applied
 *   via `driver.executeQuery()` because every PRAGMA that returns the new value (which is
 *   most of them) makes sqliter's `executeNonQuery` throw with `executeUpdateDelete returned
 *   a row` (issue: app crashes on launch on iOS — JDBC swallows the row, sqliter does not).
 *
 * Additive schema (e.g. `user_preferences`) is created idempotently after driver construction —
 * SQLDelight only runs `Schema.create` for the bundled SQL files, so newer additive tables that
 * exist outside the .sq files need explicit `CREATE TABLE IF NOT EXISTS`.
 */
public class IosDriverFactory(private val dbName: String = "puklic.db") : DriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = PuklicDatabase.Schema,
            name = dbName,
            onConfiguration = { config: DatabaseConfiguration ->
                config.copy(
                    journalMode = JournalMode.WAL,
                    extendedConfig = config.extendedConfig.copy(
                        foreignKeyConstraints = true,
                    ),
                )
            },
        )
        applyTuningPragmas(driver)
        ensureAdditiveTables(driver)
        return driver
    }

    private fun applyTuningPragmas(driver: SqlDriver) {
        listOf(
            "PRAGMA synchronous = NORMAL",
            "PRAGMA temp_store = MEMORY",
            "PRAGMA mmap_size = 268435456",
            "PRAGMA cache_size = -8000",
        ).forEach { sql ->
            driver.executeQuery(
                identifier = null,
                sql = sql,
                parameters = 0,
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Unit
                },
            )
        }
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
