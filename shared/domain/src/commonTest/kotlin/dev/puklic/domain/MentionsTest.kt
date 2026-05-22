package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "MessageMentions", "MentionTarget"
 * Spec: docs/02_domain/richtext-ast.md — "MentionTarget" sealed hierarchy
 *
 * Covers:
 *  - MessageMentions construction and field preservation
 *  - MessageMentions.everyone flag
 *  - Empty mention lists are valid
 *  - MentionTarget sealed hierarchy: User, Role, Channel, Everyone, Here
 *  - MentionTarget.Everyone and MentionTarget.Here are data objects (singletons)
 *  - Sealed when() exhaustiveness over MentionTarget
 *  - Structural equality for MessageMentions
 */
class MentionsTest {

    // ── MessageMentions construction ──────────────────────────────────────────

    /**
     * Spec: chat-model.md — MessageMentions fields
     */
    @Test
    fun `MessageMentions with all mention types preserves each list`() {
        val mentions = MessageMentions(
            users = listOf(UserId(1L), UserId(2L)),
            roles = listOf(RoleId(10L)),
            channels = listOf(ChannelId(100L), ChannelId(200L)),
            everyone = false,
        )
        assertEquals(2, mentions.users.size)
        assertEquals(listOf(UserId(1L), UserId(2L)), mentions.users)
        assertEquals(listOf(RoleId(10L)), mentions.roles)
        assertEquals(listOf(ChannelId(100L), ChannelId(200L)), mentions.channels)
        assertFalse(mentions.everyone)
    }

    @Test
    fun `MessageMentions with empty lists and everyone false is valid for plain message`() {
        val mentions = MessageMentions(
            users = emptyList(),
            roles = emptyList(),
            channels = emptyList(),
            everyone = false,
        )
        assertTrue(mentions.users.isEmpty())
        assertTrue(mentions.roles.isEmpty())
        assertTrue(mentions.channels.isEmpty())
        assertFalse(mentions.everyone)
    }

    @Test
    fun `MessageMentions with everyone true is valid for at-everyone messages`() {
        val mentions = MessageMentions(
            users = emptyList(),
            roles = emptyList(),
            channels = emptyList(),
            everyone = true,
        )
        assertTrue(mentions.everyone)
    }

    @Test
    fun `MessageMentions users list preserves order`() {
        val ids = listOf(UserId(5L), UserId(1L), UserId(3L))
        val mentions = MessageMentions(
            users = ids,
            roles = emptyList(),
            channels = emptyList(),
            everyone = false,
        )
        assertEquals(ids, mentions.users)
    }

    // ── MentionTarget.User ────────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class User(val id: UserId) : MentionTarget"
     */
    @Test
    fun `MentionTarget User preserves UserId`() {
        val target = MentionTarget.User(UserId(42L))
        assertEquals(UserId(42L), target.id)
    }

    @Test
    fun `two MentionTarget User instances with same id are equal`() {
        val a = MentionTarget.User(UserId(1L))
        val b = MentionTarget.User(UserId(1L))
        assertEquals(a, b)
    }

    @Test
    fun `MentionTarget User instances with different ids are not equal`() {
        val a = MentionTarget.User(UserId(1L))
        val b = MentionTarget.User(UserId(2L))
        assertNotEquals(a, b)
    }

    // ── MentionTarget.Role ────────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class Role(val id: RoleId) : MentionTarget"
     */
    @Test
    fun `MentionTarget Role preserves RoleId`() {
        val target = MentionTarget.Role(RoleId(77L))
        assertEquals(RoleId(77L), target.id)
    }

    @Test
    fun `two MentionTarget Role instances with same id are equal`() {
        assertEquals(MentionTarget.Role(RoleId(10L)), MentionTarget.Role(RoleId(10L)))
    }

    // ── MentionTarget.Channel ─────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class Channel(val id: ChannelId) : MentionTarget"
     */
    @Test
    fun `MentionTarget Channel preserves ChannelId`() {
        val target = MentionTarget.Channel(ChannelId(555L))
        assertEquals(ChannelId(555L), target.id)
    }

    @Test
    fun `two MentionTarget Channel instances with same id are equal`() {
        assertEquals(MentionTarget.Channel(ChannelId(1L)), MentionTarget.Channel(ChannelId(1L)))
    }

    // ── MentionTarget.Everyone and MentionTarget.Here ─────────────────────────

    /**
     * Spec: richtext-ast.md — "data object Everyone : MentionTarget"
     * data objects are singletons — referential equality holds.
     */
    @Test
    fun `MentionTarget Everyone is a singleton data object`() {
        val a: MentionTarget = MentionTarget.Everyone
        val b: MentionTarget = MentionTarget.Everyone
        // data object equality is referential (same object) + structural (equals() == true)
        assertEquals(a, b)
        assertTrue(a === b) // same object
    }

    /**
     * Spec: richtext-ast.md — "data object Here : MentionTarget"
     */
    @Test
    fun `MentionTarget Here is a singleton data object`() {
        val a: MentionTarget = MentionTarget.Here
        val b: MentionTarget = MentionTarget.Here
        assertEquals(a, b)
        assertTrue(a === b)
    }

    @Test
    fun `MentionTarget Everyone and Here are not equal to each other`() {
        assertNotEquals<MentionTarget>(MentionTarget.Everyone, MentionTarget.Here)
    }

    @Test
    fun `MentionTarget User and Everyone are not equal even if compared via interface`() {
        val user: MentionTarget = MentionTarget.User(UserId(1L))
        val everyone: MentionTarget = MentionTarget.Everyone
        assertNotEquals(user, everyone)
    }

    // ── Sealed hierarchy exhaustiveness ───────────────────────────────────────

    /**
     * Spec: richtext-ast.md — MentionTarget sealed interface with 5 subtypes
     * No else branch — compiler enforces all branches covered.
     */
    @Test
    fun `sealed MentionTarget when expression covers all five subtypes without else`() {
        val targets: List<MentionTarget> = listOf(
            MentionTarget.User(UserId(1L)),
            MentionTarget.Role(RoleId(2L)),
            MentionTarget.Channel(ChannelId(3L)),
            MentionTarget.Everyone,
            MentionTarget.Here,
        )
        val labels = targets.map { target ->
            when (target) {
                is MentionTarget.User    -> "user"
                is MentionTarget.Role    -> "role"
                is MentionTarget.Channel -> "channel"
                MentionTarget.Everyone   -> "everyone"
                MentionTarget.Here       -> "here"
            }
        }
        assertEquals(listOf("user", "role", "channel", "everyone", "here"), labels)
    }

    // ── MessageMentions structural equality ───────────────────────────────────

    @Test
    fun `two MessageMentions instances with identical fields are equal`() {
        val a = MessageMentions(
            users = listOf(UserId(1L)),
            roles = listOf(RoleId(1L)),
            channels = listOf(ChannelId(1L)),
            everyone = false,
        )
        val b = MessageMentions(
            users = listOf(UserId(1L)),
            roles = listOf(RoleId(1L)),
            channels = listOf(ChannelId(1L)),
            everyone = false,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MessageMentions instances differing only by everyone flag are not equal`() {
        val a = MessageMentions(
            users = emptyList(),
            roles = emptyList(),
            channels = emptyList(),
            everyone = false,
        )
        val b = a.copy(everyone = true)
        assertNotEquals(a, b)
    }

    @Test
    fun `MessageMentions instances differing only in users list are not equal`() {
        val a = MessageMentions(
            users = listOf(UserId(1L)),
            roles = emptyList(),
            channels = emptyList(),
            everyone = false,
        )
        val b = a.copy(users = listOf(UserId(2L)))
        assertNotEquals(a, b)
    }
}
