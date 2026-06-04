package dev.puklic.protocol.discord

/**
 * Errors emitted by [DiscordRestClient] (and surfaced via [Result.failure]).
 *
 * Tokens MUST NEVER appear in any of these fields — callers redact before constructing.
 */
public sealed class DiscordError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public data class TokenInvalid(public val detail: String = "") :
        DiscordError("Discord token rejected (401)" + if (detail.isNotBlank()) " — $detail" else "")
    public class Forbidden(public val reason: String) : DiscordError("Forbidden: $reason")
    public object NotFound : DiscordError("Resource not found (404)")
    public class RateLimited(public val retryAfterMs: Long) :
        DiscordError("Rate limited: retry after ${retryAfterMs}ms")

    public class ServerError(public val status: Int, public val body: String) :
        DiscordError("Server error $status: ${body.take(MAX_BODY_PREVIEW)}")

    public class Network(public override val cause: Throwable) :
        DiscordError("Network failure: ${cause.message}", cause)

    public class Deserialization(public override val cause: Throwable, public val raw: String) :
        DiscordError("Failed to deserialize: ${cause.message}", cause)

    private companion object {
        const val MAX_BODY_PREVIEW = 256
    }
}

/**
 * Redacts a Discord token to first four characters + ellipsis. Use whenever a token-bearing
 * value must appear in a log line or exception message.
 */
internal fun redactToken(token: String): String =
    if (token.length <= TOKEN_PREFIX_LEN) "***" else "${token.take(TOKEN_PREFIX_LEN)}..."

private const val TOKEN_PREFIX_LEN = 4
