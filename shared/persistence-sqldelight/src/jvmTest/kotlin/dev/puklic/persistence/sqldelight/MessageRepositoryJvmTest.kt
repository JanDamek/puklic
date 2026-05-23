package dev.puklic.persistence.sqldelight

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MentionTarget
import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextInline
import dev.puklic.domain.TextStyle
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class MessageRepositoryJvmTest {

    @Test
    fun persist_and_observe_returns_messages_in_ascending_timestamp_order() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        repo.persist(newMessage(id = 30, timestampMs = 3000))
        repo.persist(newMessage(id = 10, timestampMs = 1000))
        repo.persist(newMessage(id = 20, timestampMs = 2000))

        val observed = repo.observe(ChannelId(200L)).first()
        observed.map { it.id.value } shouldContainExactly listOf(10L, 20L, 30L)
    }

    @Test
    fun loadOlder_returns_messages_strictly_before_pivot() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        listOf(1000L, 2000L, 3000L, 4000L).forEachIndexed { i, ts ->
            repo.persist(newMessage(id = (i + 1).toLong(), timestampMs = ts))
        }

        val older = repo.loadOlder(ChannelId(200L), Instant.fromEpochMilliseconds(3000), limit = 10)
        older.map { it.id.value } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun upsert_replaces_existing_message_by_id() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        repo.persist(newMessage(id = 1, rawContent = "original"))
        repo.persist(newMessage(id = 1, rawContent = "edited"))

        repo.findById(MessageId(1))!!.rawContent shouldBe "edited"
        repo.observe(ChannelId(200L)).first().size shouldBe 1
    }

    @Test
    fun delete_and_clear_remove_messages() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        repo.persist(newMessage(id = 1))
        repo.persist(newMessage(id = 2))

        repo.delete(MessageId(1))
        repo.observe(ChannelId(200L)).first().map { it.id.value } shouldContainExactly listOf(2L)

        repo.clear(ChannelId(200L))
        repo.observe(ChannelId(200L)).first() shouldBe emptyList()
    }

    @Test
    fun persistAll_writes_batch_in_single_transaction() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        val batch = (1..50).map { newMessage(id = it.toLong()) }
        repo.persistAll(batch)

        repo.observe(ChannelId(200L), limit = 200).first().size shouldBe 50
    }

    // Regression: messages loaded from the cache used to have parsedContent = empty, which made
    // MessageRow fall back to raw text and show literal markdown / mention / emoji tokens. The
    // mapper now re-parses rawContent on read so the UI gets a real RichTextDocument back.
    @Test
    fun observe_returns_messages_with_parsed_richtext_for_markdown_input() = runTest {
        val db = newInMemoryDatabase().apply { seedGuildChannelUser() }
        val repo = MessageRepositoryImpl(db, Dispatchers.Unconfined)

        val raw = "**Junie Livestream #11** hi <@1273596342257582120> see " +
            "<#1365290726698778654> <a:Cup:1357748311192244495> <:youtube:1242394824427049001>"
        repo.persist(newMessage(id = 1, rawContent = raw))

        val loaded = repo.observe(ChannelId(200L)).first().single()
        loaded.parsedContent.blocks shouldNotBe emptyList<RichTextBlock>()

        val runs = (loaded.parsedContent.blocks.first() as RichTextBlock.Paragraph).runs

        // Bold marker is parsed.
        runs.filterIsInstance<RichTextInline.Text>()
            .any { TextStyle.BOLD in it.styles && it.content.contains("Junie") } shouldBe true

        // User + channel mentions are parsed (not raw <@id> text).
        runs.filterIsInstance<RichTextInline.Mention>()
            .any { it.target is MentionTarget.User } shouldBe true
        runs.filterIsInstance<RichTextInline.Mention>()
            .any { it.target is MentionTarget.Channel } shouldBe true

        // Both static and animated custom emoji parsed.
        val customEmoji = runs.filterIsInstance<RichTextInline.Emoji>()
            .map { it.ref }
            .filterIsInstance<EmojiRef.Custom>()
        customEmoji.any { it.animated && it.name == "Cup" } shouldBe true
        customEmoji.any { !it.animated && it.name == "youtube" } shouldBe true
    }
}
