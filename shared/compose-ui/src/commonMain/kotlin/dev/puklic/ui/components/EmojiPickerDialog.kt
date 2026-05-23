package dev.puklic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.puklic.domain.EmojiRef

/**
 * Modal dialog for adding a reaction. v1 only supports pasting/typing a unicode emoji
 * (or codepoint string) plus a "Recent" grid backed by in-memory history. Guild custom-emoji
 * picker is deferred until a custom-emoji repository exists.
 */
@Composable
public fun EmojiPickerDialog(
    onDismiss: () -> Unit,
    onPick: (EmojiRef) -> Unit,
    recent: List<EmojiRef> = emptyList(),
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reaction") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste emoji or type unicode") },
                    singleLine = true,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            val trimmed = input.trim()
                            if (trimmed.isNotEmpty()) onPick(EmojiRef.Unicode(trimmed))
                        },
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                )
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        contentPadding = PaddingValues(2.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    ) {
                        items(recent) { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { onPick(emoji) }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmojiVisual(emoji)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = input.trim()
                if (trimmed.isNotEmpty()) onPick(EmojiRef.Unicode(trimmed))
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
