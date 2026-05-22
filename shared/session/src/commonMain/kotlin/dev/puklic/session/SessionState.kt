package dev.puklic.session

import dev.puklic.domain.UserSummary

/** Observable lifecycle state for a [DiscordSession]. Transitions per `data-flow.md`. */
public sealed interface SessionState {
    public data object Disconnected : SessionState
    public data object ValidatingToken : SessionState
    public data class Connecting(val attempt: Int) : SessionState
    public data class Connected(val sessionId: String, val selfUser: UserSummary) : SessionState
    public data class Reconnecting(val secondsUntilRetry: Int) : SessionState
    public data object TokenInvalid : SessionState
    public data class Failed(val reason: String) : SessionState
}
