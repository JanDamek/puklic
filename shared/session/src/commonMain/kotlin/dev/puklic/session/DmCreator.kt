package dev.puklic.session

import dev.puklic.domain.Channel
import dev.puklic.ids.UserId

/**
 * Single-method seam wrapping the REST DM-creation call (issue #17). Kept separate from
 * [SessionTransport] so test doubles for DM creation stay small and so the protocol module's
 * `DiscordSessionBridge` is the only production wiring point.
 *
 * The call is idempotent on Discord's side: invoking with the same recipient twice returns
 * the existing channel (same id). The caller must not implement its own dedup.
 */
public fun interface DmCreator {
    public suspend fun createOrOpenDm(recipientId: UserId): Result<Channel>
}
