package dev.puklic.persistence.sqldelight

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.DriverFactory

/**
 * Android [DriverFactory] backed by `app.cash.sqldelight:android-driver` (`AndroidSqliteDriver`).
 *
 * The database file lives at `Context.getDatabasePath(dbName)`, which is the location reported by
 * the Android `PlatformPaths.databaseFile()`.
 *
 * Pragmas mirror the JVM and iOS factories: write-ahead logging and foreign-key enforcement are
 * set while the connection is configured, the tuning pragmas are applied afterwards through
 * `executeQuery` because Android's `execSQL` rejects statements that return a row.
 */
class AndroidDriverFactory(
    private val context: Context,
    private val dbName: String = DEFAULT_DB_NAME,
) : DriverFactory {

    override fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(
            schema = PuklicDatabase.Schema,
            context = context,
            name = dbName,
            callback = ConfiguringCallback(),
        )
        applyTuningPragmas(driver)
        ensureAdditiveTables(driver)
        return driver
    }

    private fun applyTuningPragmas(driver: SqlDriver) {
        TUNING_PRAGMAS.forEach { sql ->
            driver.executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Unit
                },
                parameters = 0,
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

    private class ConfiguringCallback : AndroidSqliteDriver.Callback(PuklicDatabase.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    private companion object {
        const val DEFAULT_DB_NAME = "puklic.db"

        val TUNING_PRAGMAS = listOf(
            "PRAGMA synchronous = NORMAL",
            "PRAGMA temp_store = MEMORY",
            "PRAGMA mmap_size = 268435456",
            "PRAGMA cache_size = -8000",
        )
    }
}
