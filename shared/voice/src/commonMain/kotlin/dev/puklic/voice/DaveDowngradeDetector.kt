package dev.puklic.voice

/**
 * Pure transition detector for DAVE downgrade events.
 *
 * Spec requirement (DAVE protocol §6 user notification): any transition from
 * an active E2EE state to a non-E2EE state MUST be surfaced to the user
 * proactively — the lock icon flipping is not enough by itself.
 *
 * This is split out as a pure object (no Flow, no Compose) so the policy can
 * be unit-tested deterministically. The Flow integration sits in the UI layer
 * (VoiceDock) — see DaveDowngradeBanner.
 */
public object DaveDowngradeDetector {

    /**
     * Returns true iff transitioning from [previous] to [current] constitutes a
     * downgrade event the user must be notified about.
     *
     * Downgrade rules:
     *  - `Active → Disabled` — server advertised an unsupported DAVE version mid-call.
     *  - `Active → Off`      — DAVE session torn down (e.g. group reset, error).
     *
     * Not downgrades:
     *  - Same-state transitions (incl. `Active → Active(newEpoch)`).
     *  - Upgrades (`Off → Active`, `Connecting → Active`).
     *  - Pre-active transitions (`Connecting → Disabled`) — the call was never
     *    promised to be E2EE in the first place; the open-lock icon is sufficient.
     */
    public fun isDowngrade(previous: DaveUiState, current: DaveUiState): Boolean {
        if (previous !is DaveUiState.Active) return false
        return when (current) {
            is DaveUiState.Active -> false
            DaveUiState.Connecting -> false
            is DaveUiState.Disabled -> true
            DaveUiState.Off -> true
        }
    }
}
