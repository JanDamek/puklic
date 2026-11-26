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
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.add_friend_placeholder
import dev.puklic.ui.resources.add_friend_send
import dev.puklic.ui.resources.add_friend_sending
import dev.puklic.ui.resources.add_friend_title
import dev.puklic.ui.resources.common_close
import org.jetbrains.compose.resources.stringResource
import dev.puklic.ui.text.asString
import dev.puklic.ui.text.UiText

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
    errorMessage: UiText?,
    successMessage: UiText?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_friend_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(Res.string.add_friend_placeholder)) },
                    singleLine = true,
                    enabled = !isSubmitting,
                )
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (successMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = successMessage.asString(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !isSubmitting && query.isNotBlank()) {
                Text(stringResource(if (isSubmitting) Res.string.add_friend_sending else Res.string.add_friend_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
    )
}
