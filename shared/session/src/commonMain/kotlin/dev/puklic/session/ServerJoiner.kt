package dev.puklic.session

/**
 * Single-method seam wrapping the REST invite-accept call (issue #80). The implementation
 * issues `POST /invites/{code}`; success means the join was accepted by Discord — the joined
 * guild then arrives asynchronously through the gateway `GUILD_CREATE` dispatch.
 */
public fun interface ServerJoiner {
    public suspend fun joinServer(code: String): Result<Unit>
}
