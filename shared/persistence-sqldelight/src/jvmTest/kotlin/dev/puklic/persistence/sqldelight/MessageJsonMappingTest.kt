package dev.puklic.persistence.sqldelight

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Reaction
import dev.puklic.domain.ReactionCountDetails
import dev.puklic.ids.ChannelId
import dev.puklic.ids.EmojiId
import dev.puklic.ids.MessageId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MessageJsonMappingTest {

    @Test
    fun attachment_roundtrips_through_persistence_unchanged() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)
        val original = newMessage(id = 1, attachments = listOf(sampleAttachment()))

        repo.persist(original)

        val loaded = repo.findById(MessageId(1))!!
        loaded.attachments shouldBe original.attachments
    }

    @Test
    fun unicode_and_custom_reactions_roundtrip() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        val reactions = listOf(
            Reaction(EmojiRef.Unicode("👍"), count = 5, me = true, countDetails = ReactionCountDetails(1, 4)),
            Reaction(EmojiRef.Custom(EmojiId(42L), "blob", animated = true), count = 2, me = false, countDetails = null),
        )
        repo.persist(newMessage(id = 1, channelId = ChannelId(200L), reactions = reactions))

        repo.findById(MessageId(1))!!.reactions shouldBe reactions
    }
}
