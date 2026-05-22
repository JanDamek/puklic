package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.puklic.db.PuklicDatabase
import dev.puklic.persistence.DriverFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * JVM implementation of [DriverFactory]. Backed by `sqlite-jdbc`. Pass [IN_MEMORY] for tests
 * that don't want to touch disk.
 *
 * Per [persistence-schema.md](../../../../../../../docs/03_infrastructure/persistence-schema.md):
 * - `journal_mode=WAL` for concurrent reads during writes
 * - `synchronous=NORMAL` (WAL-safe + fast)
 * - `foreign_keys=ON` enforced at every connection
 * - `temp_store=MEMORY`, `mmap_size=256 MB`, `cache_size=8 MB`
 *
 * The schema is created (or migrated) by SQLDelight on driver construction.
 */
class JvmDriverFactory(private val dbPath: DbPath) : DriverFactory {

    sealed interface DbPath {
        data class File(val path: Path) : DbPath
        data object InMemory : DbPath
    }

    override fun createDriver(): SqlDriver {
        val jdbcUrl = when (dbPath) {
            is DbPath.File -> {
                val absolute = dbPath.path.toAbsolutePath()
                absolute.parent?.let { parent -> if (!Files.exists(parent)) Files.createDirectories(parent) }
                "jdbc:sqlite:$absolute"
            }
            DbPath.InMemory -> "jdbc:sqlite::memory:"
        }

        // FK enforcement must be set on the connection BEFORE any query; sqlite-jdbc supports
        // this via the URL/Properties. The remaining PRAGMAs are applied via raw execute() calls
        // after the driver is constructed.
        val properties = Properties().apply {
            setProperty("foreign_keys", "true")
        }
        val driver = JdbcSqliteDriver(jdbcUrl, properties, schema = PuklicDatabase.Schema)

        applyPragmas(driver)
        return driver
    }

    private fun applyPragmas(driver: SqlDriver) {
        // In-memory DBs do not support WAL; skip pragmas that require on-disk journal files.
        if (dbPath !is DbPath.InMemory) {
            driver.execute(null, "PRAGMA journal_mode = WAL", 0)
        }
        driver.execute(null, "PRAGMA synchronous = NORMAL", 0)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        driver.execute(null, "PRAGMA temp_store = MEMORY", 0)
        driver.execute(null, "PRAGMA mmap_size = 268435456", 0)
        driver.execute(null, "PRAGMA cache_size = -8000", 0)
    }
}

/** Convenience factory for tests: an ephemeral in-memory SQLite. */
val IN_MEMORY: JvmDriverFactory.DbPath = JvmDriverFactory.DbPath.InMemory
