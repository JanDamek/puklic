package dev.puklic.session

/**
 * Feature flag controlling whether the real voice client is wired into [DiscordSession].
 *
 * Until slice 10 of Voice Phase 3.0 (see
 * `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §13), this stays `false`
 * and [DiscordSession.voiceClient] defaults to [dev.puklic.voice.NoOpVoiceClient].
 *
 * Kept as a top-level `const` for now (simplest viable seam, per repo `CLAUDE.md`
 * "Minimum-complexity, maximum-effect" rule). A richer feature-flag system can replace
 * this when more than one flag exists.
 */
public object VoiceFeatureFlag {
    public const val ENABLED: Boolean = true
}
