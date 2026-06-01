package dev.puklic.session

/**
 * Single-method seam wrapping the REST friend-request call (issue #80). Mirrors [DmCreator] —
 * keeps the protocol module's `DiscordSessionBridge` as the only production wiring point so
 * tests can supply small in-memory fakes.
 *
 * Accepts the new pomelo handle (username only, [discriminator] null) or the legacy
 * `username#discriminator` form.
 */
public fun interface FriendInviter {
    public suspend fun addFriend(username: String, discriminator: String?): Result<Unit>
}
