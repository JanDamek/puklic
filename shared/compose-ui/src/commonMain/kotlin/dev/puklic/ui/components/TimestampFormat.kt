package dev.puklic.ui.components

import dev.puklic.domain.TimestampStyle
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * Cross-platform timestamp formatting. We avoid `java.text.*` so this works on iOS / Android
 * common code. Output is English; localization is a Phase 2 concern.
 */
internal object TimestampFormat {

    /** Header timestamp in a [MessageRow]: today → HH:mm, this week → "EEE HH:mm", else "M/d/yyyy HH:mm". */
    fun header(
        instant: Instant,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val days = local.date.daysUntil(today)
        return when {
            days == 0 -> "Today at ${pad2(local.hour)}:${pad2(local.minute)}"
            days == 1 -> "Yesterday at ${pad2(local.hour)}:${pad2(local.minute)}"
            days in 2..6 -> "${shortDay(local.dayOfWeek)} ${pad2(local.hour)}:${pad2(local.minute)}"
            else -> "${local.monthNumber}/${local.dayOfMonth}/${local.year} ${pad2(local.hour)}:${pad2(local.minute)}"
        }
    }

    /** Discord `<t:UNIX:STYLE>` rendering. */
    @Suppress("CyclomaticComplexMethod")
    fun discord(
        instant: Instant,
        style: TimestampStyle,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = instant.toLocalDateTime(zone)
        return when (style) {
            TimestampStyle.SHORT_TIME -> "${pad2(local.hour)}:${pad2(local.minute)}"
            TimestampStyle.LONG_TIME -> "${pad2(local.hour)}:${pad2(local.minute)}:${pad2(local.second)}"
            TimestampStyle.SHORT_DATE -> "${local.monthNumber}/${local.dayOfMonth}/${local.year}"
            TimestampStyle.LONG_DATE -> "${monthName(local.month)} ${local.dayOfMonth}, ${local.year}"
            TimestampStyle.SHORT_DATETIME ->
                "${monthName(local.month)} ${local.dayOfMonth}, ${local.year} ${pad2(local.hour)}:${pad2(local.minute)}"
            TimestampStyle.LONG_DATETIME ->
                "${longDay(local.dayOfWeek)}, ${monthName(local.month)} ${local.dayOfMonth}, ${local.year} " +
                    "${pad2(local.hour)}:${pad2(local.minute)}"
            TimestampStyle.RELATIVE -> relative(instant, now)
        }
    }

    private fun relative(instant: Instant, now: Instant): String {
        val deltaSec = (instant.epochSeconds - now.epochSeconds)
        val past = deltaSec < 0
        val sec = abs(deltaSec)
        val (n, unit) = when {
            sec < SECONDS_PER_MIN -> sec to "second"
            sec < SECONDS_PER_HOUR -> (sec / SECONDS_PER_MIN) to "minute"
            sec < SECONDS_PER_DAY -> (sec / SECONDS_PER_HOUR) to "hour"
            sec < SECONDS_PER_MONTH -> (sec / SECONDS_PER_DAY) to "day"
            sec < SECONDS_PER_YEAR -> (sec / SECONDS_PER_MONTH) to "month"
            else -> (sec / SECONDS_PER_YEAR) to "year"
        }
        val plural = if (n == 1L) unit else "${unit}s"
        return if (past) "$n $plural ago" else "in $n $plural"
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun shortDay(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    private fun longDay(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    /**
     * Czech genitive month name, used by date separators ("21. března 2026"). This is the one
     * deliberately-localized string in the UI (issue #91); the rest of the surface stays English
     * until Phase 2 L10n.
     */
    fun czechGenitiveMonth(m: Month): String = when (m) {
        Month.JANUARY -> "ledna"
        Month.FEBRUARY -> "února"
        Month.MARCH -> "března"
        Month.APRIL -> "dubna"
        Month.MAY -> "května"
        Month.JUNE -> "června"
        Month.JULY -> "července"
        Month.AUGUST -> "srpna"
        Month.SEPTEMBER -> "září"
        Month.OCTOBER -> "října"
        Month.NOVEMBER -> "listopadu"
        Month.DECEMBER -> "prosince"
    }

    private fun monthName(m: Month): String = when (m) {
        Month.JANUARY -> "Jan"
        Month.FEBRUARY -> "Feb"
        Month.MARCH -> "Mar"
        Month.APRIL -> "Apr"
        Month.MAY -> "May"
        Month.JUNE -> "Jun"
        Month.JULY -> "Jul"
        Month.AUGUST -> "Aug"
        Month.SEPTEMBER -> "Sep"
        Month.OCTOBER -> "Oct"
        Month.NOVEMBER -> "Nov"
        Month.DECEMBER -> "Dec"
    }

    @Suppress("MagicNumber")
    private const val SECONDS_PER_MIN = 60L
    @Suppress("MagicNumber")
    private const val SECONDS_PER_HOUR = 3_600L
    @Suppress("MagicNumber")
    private const val SECONDS_PER_DAY = 86_400L
    @Suppress("MagicNumber")
    private const val SECONDS_PER_MONTH = 2_592_000L // 30 days, Discord-style approximation
    @Suppress("MagicNumber")
    private const val SECONDS_PER_YEAR = 31_536_000L // 365 days

}
