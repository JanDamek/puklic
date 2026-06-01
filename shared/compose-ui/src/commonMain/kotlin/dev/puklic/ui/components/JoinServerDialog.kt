package dev.puklic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Modal Join-Server dialog (issue #80). Accepts an invite URL (`https://discord.gg/<code>`,
 * `https://discord.com/invite/<code>`) or a bare code. The ViewModel parses the code and fires
 * `POST /invites/{code}`; on success the joined guild surfaces in the rail asynchronously via
 * the gateway `GUILD_CREATE` dispatch.
 */
@Composable
public fun JoinServerDialog(
    query: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    successMessage: String?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a server") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://discord.gg/code or just the code") },
                    singleLine = true,
                    enabled = !isSubmitting,
                )
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (successMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = successMessage,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isSubmitting && query.isNotBlank()) {
                Text(if (isSubmitting) "Joining..." else "Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
