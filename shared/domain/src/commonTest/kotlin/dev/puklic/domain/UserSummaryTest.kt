package dev.puklic.domain

import dev.puklic.ids.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "UserSummary"
 *
 * Covers:
 *  - Pomelo accounts (post-username-reform): globalName present, discriminator null
 *  - Legacy accounts: discriminator present ("0001"…"9999"), globalName null
 *  - Bot and system flags
 *  - Optional avatarHash (null and non-null)
 *  - Structural equality and inequality
 */
class UserSummaryTest {

    // ── Pomelo accounts ───────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "discriminator: String? — legacy "0001", null for pomelo accounts"
     * Post-pomelo users have no discriminator. They have a globalName (display name).
     */
    @Test
    fun `pomelo user has null discriminator and non-null globalName`() {
        val user = UserSummary(
            id = UserId(111222333444555666L),
            username = "jandam",
            globalName = "Jan Damek",
            discriminator = null,
            avatarHash = "a_deadbeef1234",
            bot = false,
            system = false,
        )
        assertNull(user.discriminator)
        assertEquals("Jan Damek", user.globalName)
    }

    @Test
    fun `pomelo user username is preserved exactly`() {
        val user = UserSummary(
            id = UserId(1L),
            username = "unique_name_42",
            globalName = "Display Name",
            discriminator = null,
            avatarHash = null,
            bot = false,
            system = false,
        )
        assertEquals("unique_name_42", user.username)
    }

    // ── Legacy accounts ───────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — legacy "0001" discriminator format
     */
    @Test
    fun `legacy user has four-digit discriminator and null globalName`() {
        val user = UserSummary(
            id = UserId(456L),
            username = "olduser",
            globalName = null,
            discriminator = "0001",
            avatarHash = null,
            bot = false,
            system = false,
        )
        assertEquals("0001", user.discriminator)
        assertNull(user.globalName)
    }

    @Test
    fun `legacy user with max discriminator 9999 is preserved`() {
        val user = UserSummary(
            id = UserId(789L),
            username = "poweruser",
            globalName = null,
            discriminator = "9999",
            avatarHash = "hash123",
            bot = false,
            system = false,
        )
        assertEquals("9999", user.discriminator)
    }

    // ── Bot and system flags ──────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "bot: Boolean, system: Boolean"
     */
    @Test
    fun `bot flag is true for bot users`() {
        val bot = UserSummary(
            id = UserId(100L),
            username = "CoolBot",
            globalName = "Cool Bot",
            discriminator = null,
            avatarHash = null,
            bot = true,
            system = false,
        )
        assertTrue(bot.bot)
        assertFalse(bot.system)
    }

    @Test
    fun `system flag is true for Discord system users`() {
        val systemUser = UserSummary(
            id = UserId(643945264868098049L), // Discord's system user snowflake
            username = "Discord",
            globalName = "Discord",
            discriminator = "0000",
            avatarHash = null,
            bot = false,
            system = true,
        )
        assertTrue(systemUser.system)
        assertFalse(systemUser.bot)
    }

    @Test
    fun `regular user has both bot and system false`() {
        val user = UserSummary(
            id = UserId(999L),
            username = "regular",
            globalName = null,
            discriminator = "1234",
            avatarHash = null,
            bot = false,
            system = false,
        )
        assertFalse(user.bot)
        assertFalse(user.system)
    }

    // ── Optional avatarHash ───────────────────────────────────────────────────

    @Test
    fun `null avatarHash is accepted for users without an avatar`() {
        val user = UserSummary(
            id = UserId(2L),
            username = "noavatar",
            globalName = null,
            discriminator = "0002",
            avatarHash = null,
            bot = false,
            system = false,
        )
        assertNull(user.avatarHash)
    }

    @Test
    fun `animated avatar hash starting with a_ is preserved`() {
        val user = UserSummary(
            id = UserId(3L),
            username = "animated",
            globalName = "Animated User",
            discriminator = null,
            avatarHash = "a_abc123def456",
            bot = false,
            system = false,
        )
        assertEquals("a_abc123def456", user.avatarHash)
    }

    // ── Structural equality ───────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "All data class instances have structural equality (Kotlin default)"
     */
    @Test
    fun `two UserSummary instances with identical fields are equal`() {
        val a = UserSummary(
            id = UserId(42L),
            username = "user",
            globalName = "User",
            discriminator = null,
            avatarHash = "hash",
            bot = false,
            system = false,
        )
        val b = UserSummary(
            id = UserId(42L),
            username = "user",
            globalName = "User",
            discriminator = null,
            avatarHash = "hash",
            bot = false,
            system = false,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `UserSummary instances differing only by id are not equal`() {
        val a = UserSummary(
            id = UserId(1L),
            username = "user",
            globalName = null,
            discriminator = "0001",
            avatarHash = null,
            bot = false,
            system = false,
        )
        val b = a.copy(id = UserId(2L))
        assertNotEquals(a, b)
    }

    @Test
    fun `UserSummary instances differing only by username are not equal`() {
        val a = UserSummary(
            id = UserId(1L),
            username = "alice",
            globalName = null,
            discriminator = "0001",
            avatarHash = null,
            bot = false,
            system = false,
        )
        val b = a.copy(username = "bob")
        assertNotEquals(a, b)
    }

    @Test
    fun `UserSummary instances differing only by bot flag are not equal`() {
        val human = UserSummary(
            id = UserId(5L),
            username = "dual",
            globalName = null,
            discriminator = "0005",
            avatarHash = null,
            bot = false,
            system = false,
        )
        val bot = human.copy(bot = true)
        assertNotEquals(human, bot)
    }
}
