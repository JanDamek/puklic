package dev.puklic.ui.components

import androidx.compose.runtime.Composable
import dev.puklic.domain.TimestampStyle
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.calendar_days_long
import dev.puklic.ui.resources.calendar_days_short
import dev.puklic.ui.resources.calendar_months_abbreviated
import dev.puklic.ui.resources.timestamp_date_long
import dev.puklic.ui.resources.timestamp_date_short
import dev.puklic.ui.resources.timestamp_datetime_long
import dev.puklic.ui.resources.timestamp_datetime_short
import dev.puklic.ui.resources.timestamp_header_date
import dev.puklic.ui.resources.timestamp_header_weekday
import dev.puklic.ui.resources.timestamp_header_today
import dev.puklic.ui.resources.timestamp_header_yesterday
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import dev.puklic.ui.resources.timestamp_relative_future_days
import dev.puklic.ui.resources.timestamp_relative_future_hours
import dev.puklic.ui.resources.timestamp_relative_future_minutes
import dev.puklic.ui.resources.timestamp_relative_future_months
import dev.puklic.ui.resources.timestamp_relative_future_seconds
import dev.puklic.ui.resources.timestamp_relative_future_years
import dev.puklic.ui.resources.timestamp_relative_past_days
import dev.puklic.ui.resources.timestamp_relative_past_hours
import dev.puklic.ui.resources.timestamp_relative_past_minutes
import dev.puklic.ui.resources.timestamp_relative_past_months
import dev.puklic.ui.resources.timestamp_relative_past_seconds
import dev.puklic.ui.resources.timestamp_relative_past_years
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Cross-platform, locale-aware timestamp formatting. We avoid `java.text.*` so this works on
 * iOS / Android common code; day names, month names, layouts and relative-time plurals come from
 * the Compose resources of the active locale (see `docs/04_ui/i18n.md`).
 */
internal object TimestampFormat {

    /** Header timestamp in a [MessageRow]: today / yesterday → "at HH:mm", this week → weekday + time, else date + time. */
    @Composable
    fun header(
        instant: Instant,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val days = local.date.daysUntil(today)
        val time = "${pad2(local.hour)}:${pad2(local.minute)}"
        return when {
            days == 0 -> stringResource(Res.string.timestamp_header_today, time)
            days == 1 -> stringResource(Res.string.timestamp_header_yesterday, time)
            days in 2..6 -> stringResource(Res.string.timestamp_header_weekday, shortDay(local.dayOfWeek), time)
            else -> stringResource(
                Res.string.timestamp_header_date,
                stringResource(
                    Res.string.timestamp_date_short,
                    local.monthNumber.toString(),
                    local.dayOfMonth.toString(),
                    local.year.toString(),
                ),
                time,
            )
        }
    }

    /** Discord `<t:UNIX:STYLE>` rendering. */
    @Composable
    @Suppress("CyclomaticComplexMethod")
    fun discord(
        instant: Instant,
        style: TimestampStyle,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(zone)
        val time = "${pad2(local.hour)}:${pad2(local.minute)}"
        return when (style) {
            TimestampStyle.SHORT_TIME -> time
            TimestampStyle.LONG_TIME -> "$time:${pad2(local.second)}"
            TimestampStyle.SHORT_DATE -> shortDate(local.monthNumber, local.dayOfMonth, local.year)
            TimestampStyle.LONG_DATE -> longDate(local.monthNumber, local.dayOfMonth, local.year)
            TimestampStyle.SHORT_DATETIME -> stringResource(
                Res.string.timestamp_datetime_short,
                longDate(local.monthNumber, local.dayOfMonth, local.year),
                time,
            )
            TimestampStyle.LONG_DATETIME -> stringResource(
                Res.string.timestamp_datetime_long,
                longDay(local.dayOfWeek),
                longDate(local.monthNumber, local.dayOfMonth, local.year),
                time,
            )
            TimestampStyle.RELATIVE -> relative(instant, now)
        }
    }

    @Composable
    private fun shortDate(month: Int, day: Int, year: Int): String =
        stringResource(Res.string.timestamp_date_short, month.toString(), day.toString(), year.toString())

    @Composable
    private fun longDate(month: Int, day: Int, year: Int): String = stringResource(
        Res.string.timestamp_date_long,
        stringArrayResource(Res.array.calendar_months_abbreviated)[month - 1],
        day.toString(),
        year.toString(),
    )

    @Composable
    private fun relative(instant: Instant, now: Instant): String {
        val deltaSec = instant.epochSeconds - now.epochSeconds
        val unit = RelativeUnit.forSeconds(abs(deltaSec))
        val quantity = (abs(deltaSec) / unit.seconds).toInt()
        val plural = if (deltaSec < 0) unit.past else unit.future
        return pluralStringResource(plural, quantity, quantity.toString())
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    @Composable
    private fun shortDay(d: DayOfWeek): String = stringArrayResource(Res.array.calendar_days_short)[d.ordinal]

    @Composable
    private fun longDay(d: DayOfWeek): String = stringArrayResource(Res.array.calendar_days_long)[d.ordinal]

    /** Relative-time unit with the number of seconds it spans and its past / future plural forms. */
    private enum class RelativeUnit(
        val seconds: Long,
        val past: PluralStringResource,
        val future: PluralStringResource,
    ) {
        SECOND(1L, Res.plurals.timestamp_relative_past_seconds, Res.plurals.timestamp_relative_future_seconds),
        MINUTE(SECONDS_PER_MIN, Res.plurals.timestamp_relative_past_minutes, Res.plurals.timestamp_relative_future_minutes),
        HOUR(SECONDS_PER_HOUR, Res.plurals.timestamp_relative_past_hours, Res.plurals.timestamp_relative_future_hours),
        DAY(SECONDS_PER_DAY, Res.plurals.timestamp_relative_past_days, Res.plurals.timestamp_relative_future_days),
        MONTH(SECONDS_PER_MONTH, Res.plurals.timestamp_relative_past_months, Res.plurals.timestamp_relative_future_months),
        YEAR(SECONDS_PER_YEAR, Res.plurals.timestamp_relative_past_years, Res.plurals.timestamp_relative_future_years),
        ;

        companion object {
            fun forSeconds(sec: Long): RelativeUnit = entries.lastOrNull { sec >= it.seconds } ?: SECOND
        }
    }

    private const val SECONDS_PER_MIN = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_MONTH = 2_592_000L
    private const val SECONDS_PER_YEAR = 31_536_000L
}
