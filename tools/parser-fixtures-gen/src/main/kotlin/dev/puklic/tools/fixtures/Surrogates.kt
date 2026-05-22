package dev.puklic.tools.fixtures

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MentionTarget
import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.RichTextInline
import dev.puklic.domain.TextStyle
import dev.puklic.domain.TimestampStyle
import dev.puklic.ids.ChannelId
import dev.puklic.ids.EmojiId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Surrogate DTOs mirroring the [:shared:domain] RichText hierarchy with @Serializable annotations.
 *
 * Rationale: ADR-0006 keeps `:shared:domain` free of the kotlinx.serialization plugin, so we cannot
 * annotate the canonical types. This module owns the JSON shape used by future test fixtures.
 *
 * Mapping is one-way for now (domain → surrogate → JSON); a `toDomain()` inverse is added so future
 * fixture-loaders can rehydrate without re-running the parser.
 */
@Serializable
data class RichTextDocumentSurrogate(val blocks: List<BlockSurrogate>)

@Serializable
sealed interface BlockSurrogate {
    @Serializable
    @SerialName("paragraph")
    data class Paragraph(val runs: List<InlineSurrogate>) : BlockSurrogate

    @Serializable
    @SerialName("code_block")
    data class CodeBlock(val language: String?, val content: String) : BlockSurrogate

    @Serializable
    @SerialName("quote")
    data class Quote(val content: List<BlockSurrogate>) : BlockSurrogate

    @Serializable
    @SerialName("heading")
    data class Heading(val level: Int, val runs: List<InlineSurrogate>) : BlockSurrogate

    @Serializable
    @SerialName("list")
    data class BulletList(val ordered: Boolean, val items: kotlin.collections.List<BulletListItem>) : BlockSurrogate

    @Serializable
    @SerialName("list_item")
    data class BulletListItem(val content: kotlin.collections.List<BlockSurrogate>) : BlockSurrogate
}

@Serializable
sealed interface InlineSurrogate {
    @Serializable
    @SerialName("text")
    data class Text(val content: String, val styles: Set<TextStyle>) : InlineSurrogate

    @Serializable
    @SerialName("inline_code")
    data class InlineCode(val content: String) : InlineSurrogate

    @Serializable
    @SerialName("link")
    data class Link(val url: String, val display: List<InlineSurrogate>) : InlineSurrogate

    @Serializable
    @SerialName("mention")
    data class Mention(val target: MentionTargetSurrogate) : InlineSurrogate

    @Serializable
    @SerialName("emoji")
    data class Emoji(val ref: EmojiRefSurrogate) : InlineSurrogate

    @Serializable
    @SerialName("spoiler")
    data class Spoiler(val content: List<InlineSurrogate>) : InlineSurrogate

    @Serializable
    @SerialName("timestamp")
    data class Timestamp(val epochSeconds: Long, val style: TimestampStyle) : InlineSurrogate

    @Serializable
    @SerialName("line_break")
    data class LineBreak(val soft: Boolean) : InlineSurrogate
}

@Serializable
sealed interface MentionTargetSurrogate {
    @Serializable @SerialName("user") data class User(val id: Long) : MentionTargetSurrogate
    @Serializable @SerialName("role") data class Role(val id: Long) : MentionTargetSurrogate
    @Serializable @SerialName("channel") data class Channel(val id: Long) : MentionTargetSurrogate
    @Serializable @SerialName("everyone") data object Everyone : MentionTargetSurrogate
    @Serializable @SerialName("here") data object Here : MentionTargetSurrogate
}

@Serializable
sealed interface EmojiRefSurrogate {
    @Serializable @SerialName("unicode") data class Unicode(val codepoint: String) : EmojiRefSurrogate

    @Serializable
    @SerialName("custom")
    data class Custom(val id: Long, val name: String, val animated: Boolean) : EmojiRefSurrogate
}

// domain → surrogate ───────────────────────────────────────────────────────────

fun RichTextDocument.toSurrogate(): RichTextDocumentSurrogate =
    RichTextDocumentSurrogate(blocks.map { it.toSurrogate() })

private fun RichTextBlock.toSurrogate(): BlockSurrogate = when (this) {
    is RichTextBlock.Paragraph -> BlockSurrogate.Paragraph(runs.map { it.toSurrogate() })
    is RichTextBlock.CodeBlock -> BlockSurrogate.CodeBlock(language, content)
    is RichTextBlock.Quote -> BlockSurrogate.Quote(content.map { it.toSurrogate() })
    is RichTextBlock.Heading -> BlockSurrogate.Heading(level, runs.map { it.toSurrogate() })
    is RichTextBlock.List -> BlockSurrogate.BulletList(ordered, items.map { it.toSurrogate() })
    is RichTextBlock.ListItem -> BlockSurrogate.BulletListItem(content.map { it.toSurrogate() })
}

private fun RichTextBlock.ListItem.toSurrogate(): BlockSurrogate.BulletListItem =
    BlockSurrogate.BulletListItem(content.map { it.toSurrogate() })

private fun RichTextInline.toSurrogate(): InlineSurrogate = when (this) {
    is RichTextInline.Text -> InlineSurrogate.Text(content, styles)
    is RichTextInline.InlineCode -> InlineSurrogate.InlineCode(content)
    is RichTextInline.Link -> InlineSurrogate.Link(url, display.map { it.toSurrogate() })
    is RichTextInline.Mention -> InlineSurrogate.Mention(target.toSurrogate())
    is RichTextInline.Emoji -> InlineSurrogate.Emoji(ref.toSurrogate())
    is RichTextInline.Spoiler -> InlineSurrogate.Spoiler(content.map { it.toSurrogate() })
    is RichTextInline.Timestamp -> InlineSurrogate.Timestamp(instant.epochSeconds, style)
    is RichTextInline.LineBreak -> InlineSurrogate.LineBreak(soft)
}

private fun MentionTarget.toSurrogate(): MentionTargetSurrogate = when (this) {
    is MentionTarget.User -> MentionTargetSurrogate.User(id.value)
    is MentionTarget.Role -> MentionTargetSurrogate.Role(id.value)
    is MentionTarget.Channel -> MentionTargetSurrogate.Channel(id.value)
    MentionTarget.Everyone -> MentionTargetSurrogate.Everyone
    MentionTarget.Here -> MentionTargetSurrogate.Here
}

private fun EmojiRef.toSurrogate(): EmojiRefSurrogate = when (this) {
    is EmojiRef.Unicode -> EmojiRefSurrogate.Unicode(codepoint)
    is EmojiRef.Custom -> EmojiRefSurrogate.Custom(id.value, name, animated)
}

// surrogate → domain (for future fixture loaders) ───────────────────────────────

fun RichTextDocumentSurrogate.toDomain(): RichTextDocument =
    RichTextDocument(blocks.map { it.toDomain() })

private fun BlockSurrogate.toDomain(): RichTextBlock = when (this) {
    is BlockSurrogate.Paragraph -> RichTextBlock.Paragraph(runs.map { it.toDomain() })
    is BlockSurrogate.CodeBlock -> RichTextBlock.CodeBlock(language, content)
    is BlockSurrogate.Quote -> RichTextBlock.Quote(content.map { it.toDomain() })
    is BlockSurrogate.Heading -> RichTextBlock.Heading(level, runs.map { it.toDomain() })
    is BlockSurrogate.BulletList -> RichTextBlock.List(ordered, items.map { it.toDomain() })
    is BlockSurrogate.BulletListItem -> RichTextBlock.ListItem(content.map { it.toDomain() })
}

private fun BlockSurrogate.BulletListItem.toDomain(): RichTextBlock.ListItem =
    RichTextBlock.ListItem(content.map { it.toDomain() })

private fun InlineSurrogate.toDomain(): RichTextInline = when (this) {
    is InlineSurrogate.Text -> RichTextInline.Text(content, styles)
    is InlineSurrogate.InlineCode -> RichTextInline.InlineCode(content)
    is InlineSurrogate.Link -> RichTextInline.Link(url, display.map { it.toDomain() })
    is InlineSurrogate.Mention -> RichTextInline.Mention(target.toDomain())
    is InlineSurrogate.Emoji -> RichTextInline.Emoji(ref.toDomain())
    is InlineSurrogate.Spoiler -> RichTextInline.Spoiler(content.map { it.toDomain() })
    is InlineSurrogate.Timestamp -> RichTextInline.Timestamp(
        kotlinx.datetime.Instant.fromEpochSeconds(epochSeconds),
        style,
    )
    is InlineSurrogate.LineBreak -> RichTextInline.LineBreak(soft)
}

private fun MentionTargetSurrogate.toDomain(): MentionTarget = when (this) {
    is MentionTargetSurrogate.User -> MentionTarget.User(UserId(id))
    is MentionTargetSurrogate.Role -> MentionTarget.Role(RoleId(id))
    is MentionTargetSurrogate.Channel -> MentionTarget.Channel(ChannelId(id))
    MentionTargetSurrogate.Everyone -> MentionTarget.Everyone
    MentionTargetSurrogate.Here -> MentionTarget.Here
}

private fun EmojiRefSurrogate.toDomain(): EmojiRef = when (this) {
    is EmojiRefSurrogate.Unicode -> EmojiRef.Unicode(codepoint)
    is EmojiRefSurrogate.Custom -> EmojiRef.Custom(EmojiId(id), name, animated)
}
