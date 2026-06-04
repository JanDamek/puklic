package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Issue #91 message-stream dividers: a centered Czech date separator and the red "NOVÉ" unread
 * marker. Pure helpers ([czechLongDate], [isNewDay]) are unit-tested; the composables render the
 * approved Discord-parity styling.
 */

private const val DIVIDER_VERTICAL_PADDING_DP = 8

/** "21. března 2026" — Czech genitive month, day without a leading zero. */
fun czechLongDate(date: LocalDate): String =
    "${date.dayOfMonth}. ${TimestampFormat.czechGenitiveMonth(date.month)} ${date.year}"

/**
 * True when [current] falls on a different calendar day (in [zone]) than its older sibling
 * [previousOlder], or when there is no older sibling (top of the stream). Used to decide whether a
 * [DateSeparator] precedes the message.
 */
fun isNewDay(current: Instant, previousOlder: Instant?, zone: TimeZone): Boolean =
    previousOlder == null ||
        current.toLocalDateTime(zone).date != previousOlder.toLocalDateTime(zone).date

/** Centered muted date label on a thin [outlineVariant][androidx.compose.material3.ColorScheme.outlineVariant] rule. */
@Composable
fun DateSeparator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DIVIDER_VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Full-width red rule with a right-aligned red "NOVÉ" chip, anchored above the first unread message. */
@Composable
fun UnreadDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DIVIDER_VERTICAL_PADDING_DP.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.error)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "NOVÉ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
