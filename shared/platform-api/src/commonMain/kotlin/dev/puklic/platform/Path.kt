package dev.puklic.platform

/**
 * Platform-agnostic path representation.
 *
 * Kept as a [String] alias to avoid pulling kotlinx-io into every consumer of [:shared:platform-api].
 * `actual` implementations translate between this and their native path types
 * (e.g. `java.nio.file.Path`, `NSURL`).
 */
typealias Path = String
