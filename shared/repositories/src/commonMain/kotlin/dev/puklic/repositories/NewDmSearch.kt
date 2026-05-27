package dev.puklic.repositories

import dev.puklic.domain.DmChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.UserId
internal const val NEW_DM_DEFAULT_LIMIT: Int = 25

/**
 * Functional surface for name-search over cached users. Implemented by [UserOrchestrator].
 * The seam keeps [NewDmSearch] unit-testable without depending on the persistence module.
 */
public fun interface CachedUserSearch {
    public suspend fun searchByName(query: String, limit: Int): List<dev.puklic.domain.UserSummary>
}

/**
 * Source-of-truth search for the New-DM picker (issue #17). Unions three caches kept by the
 * running session and exposes a single query-by-name function:
 *
 * 1. Recipients of existing DM channels ([DmListOrchestrator.dms]) — "recent DMs"
 * 2. Persisted cached users ([UserRepository.searchByName]) — READY users, message authors,
 *    mentions and members of mutual guilds (mutual-guild members surface here because their
 *    messages have been observed in shared channels)
 * 3. The self user is excluded (you cannot DM yourself; Discord rejects with 50007)
 *
 * The picker is client-side only — Discord ToS / "What Puklic IS NOT" forbids user
 * enumeration of the directory. Bots / system users are filtered out (manual DMs are aimed
 * at real users; bot DMs go through bot-specific affordances elsewhere).
 *
 * Pure with respect to its inputs — does not mutate state, easy to unit-test.
 */
public class NewDmSearch(
    private val users: CachedUserSearch,
    private val dms: DmListOrchestrator,
    private val selfUserId: () -> UserId?,
    private val limit: Int = NEW_DM_DEFAULT_LIMIT,
) {
    public suspend fun search(query: String): List<UserSummary> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val self = selfUserId()
        val fromPersisted = users.searchByName(trimmed, limit)
        val fromDms = dms.dms.value
            .flatMap(DmChannel::recipients)
            .filter { matches(it, trimmed) }
        val merged = (fromDms + fromPersisted)
            .filter { it.id != self && !it.bot && !it.system }
            .distinctBy { it.id }
        return merged.sortedWith(rank(trimmed)).take(limit)
    }

    private fun matches(user: UserSummary, query: String): Boolean {
        val q = query.lowercase()
        return user.username.lowercase().contains(q) ||
            (user.globalName?.lowercase()?.contains(q) == true)
    }

    private fun rank(query: String): Comparator<UserSummary> {
        val q = query.lowercase()
        return compareBy(
            // Names starting with the query rank above contains-only matches.
            { !startsWith(it, q) },
            // Prefer global_name over fallback to username for the alpha tiebreaker.
            { (it.globalName ?: it.username).lowercase() },
        )
    }

    private fun startsWith(user: UserSummary, qLower: String): Boolean =
        user.username.lowercase().startsWith(qLower) ||
            (user.globalName?.lowercase()?.startsWith(qLower) == true)
}
