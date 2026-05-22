package dev.puklic.persistence.sqldelight

import kotlinx.serialization.json.Json

/**
 * Strict JSON instance used exclusively by the persistence layer to encode/decode the embedded
 * JSON columns (`attachments_json`, `embeds_json`, `reactions_json`, mentions_*).
 *
 * Per ADR-0006: `ignoreUnknownKeys = false` for all internal schemas under our control. The
 * lenient parsing is reserved for the Discord protocol module. If the persistence schema evolves,
 * we add a new column or a migration — we never silently swallow unknown fields here.
 *
 * `internal` to this module so no other layer can accidentally adopt it.
 */
internal val PersistenceJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    explicitNulls = false
}
