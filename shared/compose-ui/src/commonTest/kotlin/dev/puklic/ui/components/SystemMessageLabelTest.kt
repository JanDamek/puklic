package dev.puklic.ui.components

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.MessageType
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemMessageLabelTest {

    private fun msg(type: MessageType, displayName: String = "Tester"): ChatMessage = ChatMessage(
        id = MessageId(1L),
        channelId = ChannelId(2L),
        author = UserSummary(
            id = UserId(3L),
            username = "tester",
            globalName = displayName,
            discriminator = null,
            avatarHash = null,
            bot = false,
            system = false,
        ),
        rawContent = "",
        parsedContent = RichTextDocument(emptyList()),
        attachments = emptyList(),
        embeds = emptyList(),
        reactions = emptyList(),
        mentions = MessageMentions(emptyList(), emptyList(), emptyList(), everyone = false),
        flags = MessageFlags.NONE,
        type = type,
        timestamp = Instant.fromEpochMilliseconds(0L),
        editedTimestamp = null,
        referencedMessage = null,
    )

    @Test
    fun call_type_renders_started_a_call_line() {
        assertEquals("Tester started a call.", systemMessageLabel(msg(MessageType.CALL)))
    }

    @Test
    fun default_type_returns_null_to_let_bubble_render() {
        assertNull(systemMessageLabel(msg(MessageType.DEFAULT)))
    }

    @Test
    fun reply_type_returns_null_to_let_bubble_render() {
        assertNull(systemMessageLabel(msg(MessageType.REPLY)))
    }

    @Test
    fun unknown_type_returns_null_to_avoid_blank_system_row() {
        assertNull(systemMessageLabel(msg(MessageType.UNKNOWN)))
    }

    @Test
    fun recipient_add_renders_added_someone_line() {
        assertEquals(
            "Tester added someone to the group.",
            systemMessageLabel(msg(MessageType.RECIPIENT_ADD)),
        )
    }
}
