package dev.puklic.repositories

/**
 * Per-tier maximum upload size for a single attachment on Discord, as of 2026-05-27.
 *
 *  - Default (DM, group DM, or guild without a boost tier): 25 MiB
 *  - Guild boost tier 1: 25 MiB
 *  - Guild boost tier 2: 50 MiB
 *  - Guild boost tier 3: 100 MiB
 *
 * Per architect comment on issue #23: single source of truth for the size limit. The function is
 * pure and side-effect-free; callers resolve the guild's premium tier and pass it in. `null`
 * (DM, unknown guild, or guild not yet loaded) always falls back to the default 25 MiB so the
 * client never silently uploads bytes Discord will reject.
 */
public object AttachmentLimits {

    public const val MAX_ATTACHMENT_SIZE_DEFAULT_BYTES: Long = 25L * BYTES_PER_MIB
    public const val MAX_ATTACHMENT_SIZE_TIER1_BYTES: Long = 25L * BYTES_PER_MIB
    public const val MAX_ATTACHMENT_SIZE_TIER2_BYTES: Long = 50L * BYTES_PER_MIB
    public const val MAX_ATTACHMENT_SIZE_TIER3_BYTES: Long = 100L * BYTES_PER_MIB

    public fun maxBytesFor(guildPremiumTier: Int?): Long = when (guildPremiumTier) {
        TIER_3 -> MAX_ATTACHMENT_SIZE_TIER3_BYTES
        TIER_2 -> MAX_ATTACHMENT_SIZE_TIER2_BYTES
        TIER_1 -> MAX_ATTACHMENT_SIZE_TIER1_BYTES
        else -> MAX_ATTACHMENT_SIZE_DEFAULT_BYTES
    }

    private const val TIER_1 = 1
    private const val TIER_2 = 2
    private const val TIER_3 = 3
}

private const val BYTES_PER_KIB: Long = 1024L
private const val BYTES_PER_MIB: Long = BYTES_PER_KIB * BYTES_PER_KIB
