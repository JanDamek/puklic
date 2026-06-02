package dev.puklic.ui.screens.main

import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.repositories.ReadStateView

/**
 * Issue #81 UI aggregation helpers. Pure functions so the unread/mention badge logic can be
 * exercised without spinning up the full [dev.puklic.repositories.Orchestrators] bundle. The
 * [MainViewModel] composes the StateFlows; these helpers describe what each emission means.
 */

/**
 * Filter [readState] to the DM channels in [dms]. Channels with no entry are omitted so the
 * UI can `map[id]` and treat null as "no unread".
 */
internal fun computeDmUnread(
    dms: List<DmChannel>,
    readState: Map<ChannelId, ReadStateView>,
): Map<ChannelId, ReadStateView> {
    if (dms.isEmpty() || readState.isEmpty()) return emptyMap()
    val ids = dms.mapTo(mutableSetOf()) { it.id }
    return readState.filterKeys { it in ids }
}

/**
 * True iff any channel in [channels] (assumed to belong to [guildId]) has a positive mention
 * count according to [readState]. The guild rail dot is mention-only (architect design) — plain
 * unread (no @mention) is intentionally NOT surfaced at the rail level to keep the visual quiet.
 */
internal fun guildHasMention(
    guildId: GuildId,
    channels: List<GuildChannel>,
    readState: Map<ChannelId, ReadStateView>,
): Boolean {
    if (readState.isEmpty()) return false
    return channels.any { channel ->
        channel.guildId == guildId && (readState[channel.id]?.mentionCount ?: 0) > 0
    }
}
