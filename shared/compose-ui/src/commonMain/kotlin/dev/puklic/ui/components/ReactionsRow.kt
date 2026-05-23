package dev.puklic.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Reaction

private val ChipHeight = 24.dp
private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Horizontal flowing row of reaction chips for a single message. Hidden when [reactions] is empty
 * (v1 has no way to seed the first reaction from this row alone — the picker is reachable only
 * once at least one reaction exists). Each chip is clickable and toggles via [onToggle]; a final
 * `+` chip opens the emoji picker via [onPickerOpen].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ReactionsRow(
    reactions: List<Reaction>,
    onToggle: (EmojiRef) -> Unit,
    onPickerOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            ReactionChip(reaction = reaction, onClick = { onToggle(reaction.emoji) })
        }
        AddReactionChip(onClick = onPickerOpen)
    }
}

@Composable
private fun ReactionChip(reaction: Reaction, onClick: () -> Unit) {
    val bg = if (reaction.me) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val border = if (reaction.me) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    Surface(
        shape = ChipShape,
        color = bg,
        border = border,
        modifier = Modifier
            .height(ChipHeight)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmojiVisual(reaction.emoji)
            Spacer(Modifier.width(4.dp))
            Text(
                text = reaction.count.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AddReactionChip(onClick: () -> Unit) {
    Surface(
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .height(ChipHeight)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "+",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun EmojiVisual(emoji: EmojiRef) {
    when (emoji) {
        is EmojiRef.Unicode -> Text(text = emoji.codepoint, fontSize = 14.sp)
        is EmojiRef.Custom -> {
            val ext = if (emoji.animated) "gif" else "png"
            AsyncImage(
                model = "https://cdn.discordapp.com/emojis/${emoji.id.value}.$ext",
                contentDescription = emoji.name,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
