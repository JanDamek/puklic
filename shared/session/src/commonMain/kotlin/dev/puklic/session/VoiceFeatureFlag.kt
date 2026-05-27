package dev.puklic.session

/**
 * Feature flag controlling whether the real voice client is wired into [DiscordSession].
 * When `false`, [DiscordSession.voiceClient] defaults to [dev.puklic.voice.NoOpVoiceClient].
 *
 * Modelled as a top-level `const` — minimal seam, no DI ceremony — because the flag is
 * build-time and there is exactly one of it. Per repo `CLAUDE.md` "Minimum-complexity,
 * maximum-effect" rule, a richer feature-flag system is introduced only when a second
 * flag actually exists.
 */
public object VoiceFeatureFlag {
    public const val ENABLED: Boolean = true
}
