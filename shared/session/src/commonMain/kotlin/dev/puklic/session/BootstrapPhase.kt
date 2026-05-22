package dev.puklic.session

/**
 * Phase of the auto-restore flow on app startup.
 *
 * The UI observes [SessionManager.bootstrap] together with [SessionManager.activeSession] to
 * decide whether to show a brief loading screen (while a stored token is being validated)
 * versus the LoginScreen (no token, or stored token already proven invalid).
 */
public enum class BootstrapPhase {
    /** Auto-restore has not been started in this process. */
    Idle,

    /** [SessionManager.loadStoredSession] is in flight (reading storage or validating token). */
    Loading,

    /** Auto-restore finished; observe [SessionManager.activeSession] for the outcome. */
    Done,
}
