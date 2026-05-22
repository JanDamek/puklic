package dev.puklic.domain

import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "Guild"
 *
 * Covers:
 *  - Construction with all mandatory fields
 *  - Optional iconHash (null and non-null)
 *  - Optional memberCount (null for guilds without member count)
 *  - features: Set<GuildFeature> — empty, single, multiple
 *  - Structural equality and inequality
 *  - GuildFeature enum values used by spec (COMMUNITY, VERIFIED, …)
 */
class GuildTest {

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — Guild with typical values including icon and member count
     */
    @Test
    fun `Guild construction with all fields preserves each value`() {
        val guild = Guild(
            id = GuildId(123456789012345678L),
            name = "Puklic Dev",
            iconHash = "a_cafebabe0000",
            ownerId = UserId(987654321L),
            features = setOf(GuildFeature.COMMUNITY, GuildFeature.VERIFIED),
            memberCount = 4200,
        )
        assertEquals(GuildId(123456789012345678L), guild.id)
        assertEquals("Puklic Dev", guild.name)
        assertEquals("a_cafebabe0000", guild.iconHash)
        assertEquals(UserId(987654321L), guild.ownerId)
        assertEquals(2, guild.features.size)
        assertTrue(guild.features.contains(GuildFeature.COMMUNITY))
        assertTrue(guild.features.contains(GuildFeature.VERIFIED))
        assertEquals(4200, guild.memberCount)
    }

    // ── Optional fields ───────────────────────────────────────────────────────

    @Test
    fun `Guild with null iconHash is accepted`() {
        val guild = Guild(
            id = GuildId(1L),
            name = "No Icon Guild",
            iconHash = null,
            ownerId = UserId(1L),
            features = emptySet(),
            memberCount = null,
        )
        assertNull(guild.iconHash)
    }

    @Test
    fun `Guild with null memberCount is accepted for guilds where count is unavailable`() {
        val guild = Guild(
            id = GuildId(2L),
            name = "Unknown Size Guild",
            iconHash = null,
            ownerId = UserId(2L),
            features = emptySet(),
            memberCount = null,
        )
        assertNull(guild.memberCount)
    }

    // ── features: Set<GuildFeature> ───────────────────────────────────────────

    @Test
    fun `Guild with empty features set is valid`() {
        val guild = Guild(
            id = GuildId(3L),
            name = "Plain Guild",
            iconHash = null,
            ownerId = UserId(3L),
            features = emptySet(),
            memberCount = 1,
        )
        assertTrue(guild.features.isEmpty())
    }

    @Test
    fun `Guild features set contains only unique values — Set semantics`() {
        // Providing the same feature twice via a set construction — set removes duplicates
        val features = setOf(GuildFeature.COMMUNITY, GuildFeature.COMMUNITY)
        val guild = Guild(
            id = GuildId(4L),
            name = "Dedup Guild",
            iconHash = null,
            ownerId = UserId(4L),
            features = features,
            memberCount = 10,
        )
        assertEquals(1, guild.features.size)
    }

    @Test
    fun `Guild with all representative features in set preserves all`() {
        val allFeatures = setOf(
            GuildFeature.COMMUNITY,
            GuildFeature.VERIFIED,
            GuildFeature.PARTNERED,
            GuildFeature.DISCOVERABLE,
        )
        val guild = Guild(
            id = GuildId(5L),
            name = "Feature-Rich Guild",
            iconHash = "deadbeef",
            ownerId = UserId(5L),
            features = allFeatures,
            memberCount = 100_000,
        )
        assertEquals(4, guild.features.size)
        assertTrue(guild.features.containsAll(allFeatures))
    }

    // ── GuildFeature enum ─────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "features: Set<GuildFeature> // COMMUNITY, VERIFIED, ..."
     * The enum must expose at least COMMUNITY and VERIFIED as named constants.
     */
    @Test
    fun `GuildFeature enum provides COMMUNITY and VERIFIED constants`() {
        // If these names don't exist in the enum, this line won't compile — compile-fail = spec-fail
        val community: GuildFeature = GuildFeature.COMMUNITY
        val verified: GuildFeature = GuildFeature.VERIFIED
        assertNotEquals(community, verified)
    }

    @Test
    fun `GuildFeature enum values are distinct`() {
        val values = GuildFeature.entries.toSet()
        // The set size must equal the list size — no duplicate ordinal values
        assertEquals(GuildFeature.entries.size, values.size)
    }

    // ── Structural equality ───────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "All data class instances have structural equality (Kotlin default)"
     */
    @Test
    fun `two Guild instances with identical fields are equal`() {
        val a = Guild(
            id = GuildId(10L),
            name = "Equal Guild",
            iconHash = "icon",
            ownerId = UserId(10L),
            features = setOf(GuildFeature.COMMUNITY),
            memberCount = 50,
        )
        val b = Guild(
            id = GuildId(10L),
            name = "Equal Guild",
            iconHash = "icon",
            ownerId = UserId(10L),
            features = setOf(GuildFeature.COMMUNITY),
            memberCount = 50,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Guild instances differing only by name are not equal`() {
        val a = Guild(
            id = GuildId(20L),
            name = "Alpha",
            iconHash = null,
            ownerId = UserId(20L),
            features = emptySet(),
            memberCount = null,
        )
        val b = a.copy(name = "Beta")
        assertNotEquals(a, b)
    }

    @Test
    fun `Guild instances differing only by features set are not equal`() {
        val base = Guild(
            id = GuildId(30L),
            name = "Feature Delta",
            iconHash = null,
            ownerId = UserId(30L),
            features = emptySet(),
            memberCount = null,
        )
        val withCommunity = base.copy(features = setOf(GuildFeature.COMMUNITY))
        assertNotEquals(base, withCommunity)
    }
}
