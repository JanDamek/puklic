package dev.puklic.ids

import kotlinx.datetime.Instant

/** Discord's custom epoch: 2015-01-01T00:00:00Z in Unix epoch milliseconds. */
const val DISCORD_EPOCH: Long = 1_420_070_400_000L

/** Extracts the creation timestamp from a Discord snowflake ID. */
fun Long.snowflakeTimestamp(): Instant =
    Instant.fromEpochMilliseconds((this shr 22) + DISCORD_EPOCH)
