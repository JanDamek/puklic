package dev.puklic.repositories

import dev.puklic.ids.ChannelId

/**
 * Non-blocking peek at the latest known visibility state for a channel. Owned by
 * [ChannelOrchestrator]; injected into [NotificationDispatcher] (and any future caller that
 * must gate cross-cutting behaviour on visibility).
 *
 * Returns `null` when no visibility data has been computed for [channelId] yet (e.g.
 * bootstrap, DM channels, or channels in a guild whose roles have not arrived). Callers
 * decide policy:
 *  - UI uses the per-guild `channelsForGuild` Flow, permissive on bootstrap (see §6 of the
 *    architect report).
 *  - [NotificationDispatcher] treats `null` as "suppress" (conservative; no leak).
 *
 * See architect-report 2026-05-24-channel-permission-design.md §6a.
 */
public fun interface VisibilityCheck {
    public fun isChannelVisible(channelId: ChannelId): Boolean?
}
