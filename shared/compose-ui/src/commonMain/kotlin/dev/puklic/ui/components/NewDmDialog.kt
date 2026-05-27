package dev.puklic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId

/**
 * Modal "Start new DM" picker (issue #17). Search field at the top, list of matches below.
 *
 * The dialog is purely presentational — search and DM-creation side-effects live in the
 * owning ViewModel ([dev.puklic.ui.screens.main.MainViewModel.newDm]). Click on a result
 * triggers [onPick] with that user's id; the ViewModel then calls Discord REST and switches
 * the channel selection on success.
 *
 * Results are the local-cache union of DM recipients + persisted cached users (READY users,
 * message authors, mentions, mutual-guild members). Discord ToS forbids server-side user
 * directory enumeration, so an empty cache returns an empty list.
 */
@Composable
public fun NewDmDialog(
    query: String,
    results: List<UserSummary>,
    isSubmitting: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (UserId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a direct message") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by name") },
                    singleLine = true,
                    enabled = !isSubmitting,
                )
                Spacer(Modifier.height(12.dp))
                ResultsBody(
                    query = query,
                    results = results,
                    onPick = onPick,
                )
            }
        },
        confirmButton = {
            // Empty confirm slot — selection happens on row click. Slot kept for a11y / focus
            // traversal so the dialog still has a "primary action" anchor.
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Close") }
        },
    )
}

@Composable
private fun ResultsBody(
    query: String,
    results: List<UserSummary>,
    onPick: (UserId) -> Unit,
) {
    when {
        query.isBlank() -> HintLine("Type a name to search your contacts")
        results.isEmpty() -> HintLine("No matches in your known users")
        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(results, key = { "dm-pick-${it.id.value}" }) { user -> PickerRow(user, onPick) }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun PickerRow(user: UserSummary, onPick: (UserId) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { onPick(user.id) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PuklicAvatar(user = user, size = 28.dp)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                user.globalName ?: user.username,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (user.globalName != null) {
                Text(
                    "@${user.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
