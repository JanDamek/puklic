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
 * Modal Add-Friend dialog (issue #80). Single text field accepts a pomelo handle ("name"), a
 * legacy "name#1234" pair, or a raw numeric user id. Submit fires the REST
 * `POST /users/@me/relationships` via the owning ViewModel; success / failure feedback is
 * rendered inline (errorMessage / successMessage) so the dialog stays self-contained.
 */
@Composable
public fun AddFriendDialog(
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
        title = { Text("Add a friend") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("username or username#1234") },
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
                Text(if (isSubmitting) "Sending..." else "Send Request")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
