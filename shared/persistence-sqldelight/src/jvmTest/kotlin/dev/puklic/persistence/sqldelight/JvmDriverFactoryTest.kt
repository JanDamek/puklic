package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class JvmDriverFactoryTest {

    private val tempDir = createTempDirectory(prefix = "puklic-driver-")

    @AfterTest
    fun cleanup() {
        if (tempDir.exists()) tempDir.deleteRecursively()
    }

    @Test
    fun creates_database_file_at_requested_path() {
        val dbFile = tempDir.resolve("puklic.db")
        val driver = JvmDriverFactory(JvmDriverFactory.DbPath.File(dbFile)).createDriver()

        // Drive schema creation by touching the database.
        PuklicDatabase(driver).schemaVersionQueries.selectCurrent().executeAsOneOrNull()

        dbFile.exists() shouldBe true
        driver.close()
    }

    @Test
    fun applies_wal_journal_mode_on_disk_databases() {
        val dbFile = tempDir.resolve("wal-check.db")
        val driver = JvmDriverFactory(JvmDriverFactory.DbPath.File(dbFile)).createDriver()
        PuklicDatabase(driver) // trigger schema

        val mode = readSinglePragma(driver, "journal_mode")
        mode.lowercase() shouldBe "wal"
        driver.close()
    }

    @Test
    fun enforces_foreign_keys() {
        val driver = JvmDriverFactory(JvmDriverFactory.DbPath.InMemory).createDriver()
        PuklicDatabase(driver)

        val fk = readSinglePragma(driver, "foreign_keys")
        // SQLite returns "1" when on, "0" when off.
        fk shouldContain "1"
        driver.close()
    }

    @Test
    fun in_memory_driver_works_without_filesystem() {
        val driver = JvmDriverFactory(JvmDriverFactory.DbPath.InMemory).createDriver()
        val db = PuklicDatabase(driver)

        db.schemaVersionQueries.insert(version = 1, applied_at = 0L)
        db.schemaVersionQueries.selectCurrent().executeAsOne().version shouldBe 1L
        driver.close()
    }

    private fun readSinglePragma(driver: app.cash.sqldelight.db.SqlDriver, name: String): String {
        // SqlDriver.executeQuery requires an identifier + mapper. We use a non-null id to avoid
        // re-prepare on every call.
        val result = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) ?: "" else "",
                )
            },
            parameters = 0,
        )
        return result.value
    }
}
