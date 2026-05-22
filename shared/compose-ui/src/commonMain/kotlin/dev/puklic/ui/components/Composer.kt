package dev.puklic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Format actions per `docs/04_ui/component-library.md` §Composer. */
public enum class FormatCommand { BOLD, ITALIC, STRIKETHROUGH, INLINE_CODE, CODE_BLOCK, LINK, SPOILER }

@Composable
public fun Composer(
    draft: String,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    @Suppress("UnusedParameter") onFormat: (FormatCommand) -> Unit = {},
    @Suppress("UnusedParameter") onAttachClick: () -> Unit = {},
    @Suppress("UnusedParameter") onEmojiClick: () -> Unit = {},
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Column(modifier = modifier.fillMaxWidth().padding(spacing.space4)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(placeholder) },
            enabled = isEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    @Suppress("UNUSED_EXPRESSION") onSubmit
}
