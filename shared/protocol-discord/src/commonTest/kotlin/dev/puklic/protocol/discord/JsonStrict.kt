package dev.puklic.protocol.discord

import kotlinx.serialization.json.Json

/**
 * Strict JSON instance — used for ALL test fixtures + mapper tests.
 *
 * Per ADR-0006: production [DiscordJson] is lenient, tests are strict. If Discord adds a field
 * that appears in a fixture, this strict deserializer fails — that's the desired schema-drift gate.
 */
internal val DiscordJsonStrict: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    explicitNulls = false
}
