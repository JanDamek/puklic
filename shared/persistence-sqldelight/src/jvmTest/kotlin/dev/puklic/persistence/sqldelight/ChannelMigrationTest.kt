package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.puklic.db.PuklicDatabase
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Regression for commit 1d550c1 which added `bitrate` + `user_limit` to the channel table
 * without bumping SchemaVersion. Existing on-disk DBs need a migration (v1 -> v2) to gain
 * the new columns instead of throwing on every channel upsert.
 *
 * This test materialises a v1-shaped DB by hand (the schema as it existed before the voice
 * commit), then opens it with the production [JvmDriverFactory]. SQLDelight must apply the
 * 1.sqm migration so that the new columns become writable.
 */
@OptIn(ExperimentalPathApi::class)
class ChannelMigrationTest {

    private val tempDir = createTempDirectory(prefix = "puklic-migration-")

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun v1_database_is_migrated_to_v2_with_voice_columns() {
        val dbFile = tempDir.resolve("legacy.db")

        // 1. Bootstrap a v1-shaped DB without bitrate/user_limit and stamp user_version = 1.
        val raw = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePathString()}", Properties())
        raw.execute(
            null,
            """
            CREATE TABLE channel (
                id INTEGER NOT NULL PRIMARY KEY,
                guild_id INTEGER,
                parent_id INTEGER,
                type INTEGER NOT NULL,
                name TEXT,
                topic TEXT,
                position INTEGER,
                rate_limit_per_user INTEGER DEFAULT 0,
                nsfw INTEGER DEFAULT 0,
                last_message_id INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        raw.execute(
            null,
            "INSERT INTO channel(id, type, updated_at) VALUES (42, 0, 0)",
            0,
        )
        // The message table is required so subsequent migrations (2.sqm — `type` column) can ALTER it.
        raw.execute(
            null,
            """
            CREATE TABLE message (
                id INTEGER NOT NULL PRIMARY KEY,
                channel_id INTEGER NOT NULL,
                author_id INTEGER NOT NULL,
                raw_content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                edited_timestamp INTEGER,
                flags INTEGER NOT NULL DEFAULT 0,
                reference_message_id INTEGER,
                reference_channel_id INTEGER,
                reference_type INTEGER,
                mentions_everyone INTEGER DEFAULT 0,
                attachments_json TEXT NOT NULL DEFAULT '[]',
                embeds_json TEXT NOT NULL DEFAULT '[]',
                reactions_json TEXT NOT NULL DEFAULT '[]',
                mentions_users_json TEXT NOT NULL DEFAULT '[]',
                mentions_roles_json TEXT NOT NULL DEFAULT '[]',
                mentions_channels_json TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
            0,
        )
        raw.execute(null, "PRAGMA user_version = 1", 0)
        raw.close()

        Files.exists(dbFile) shouldBe true

        // 2. Open via production factory — SQLDelight should auto-migrate v1 -> v2.
        val driver = JvmDriverFactory(JvmDriverFactory.DbPath.File(dbFile)).createDriver()
        val db = PuklicDatabase(driver)

        // 3. Existing row survives, new columns default to NULL.
        val migrated = db.channelQueries.selectById(42L).executeAsOne()
        migrated.bitrate shouldBe null
        migrated.user_limit shouldBe null

        // 4. New writes with voice columns succeed (the original SQLITE_ERROR scenario).
        db.channelQueries.upsert(
            id = 99L,
            guild_id = null,
            parent_id = null,
            type = 2L,
            name = "voice",
            topic = null,
            position = 0L,
            rate_limit_per_user = 0L,
            nsfw = 0L,
            bitrate = 64_000L,
            user_limit = 10L,
            last_message_id = null,
            updated_at = 0L,
        )
        val voice = db.channelQueries.selectById(99L).executeAsOne()
        voice.bitrate shouldBe 64_000L
        voice.user_limit shouldBe 10L

        driver.close()
    }
}
