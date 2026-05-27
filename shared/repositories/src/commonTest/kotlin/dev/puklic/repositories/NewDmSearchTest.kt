package dev.puklic.repositories

import dev.puklic.domain.DmChannel
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewDmSearchTest {

    private fun user(
        id: Long,
        username: String,
        globalName: String? = null,
        bot: Boolean = false,
        system: Boolean = false,
    ): UserSummary = UserSummary(
        id = UserId(id),
        username = username,
        globalName = globalName,
        discriminator = null,
        avatarHash = null,
        bot = bot,
        system = system,
    )

    private suspend fun fixture(
        ctx: CoroutineContext,
        job: Job,
        userBase: List<UserSummary> = emptyList(),
        dmRecipients: List<UserSummary> = emptyList(),
        self: UserId? = null,
        limit: Int = 25,
    ): NewDmSearch {
        val users = FakeUserRepository()
        val gateway = FakeGatewayEventSource()
        val scope = CoroutineScope(ctx + job)
        val dms = DmListOrchestrator(scope, gateway)
        users.persistAll(userBase)
        if (dmRecipients.isNotEmpty()) {
            val ch = DmChannel(ChannelId(10_000L), dmRecipients, lastMessageId = null)
            gateway.emit(GatewayDomainEvent.ChannelCreated(ch))
            dms.dms.first { it.isNotEmpty() }
        }
        return NewDmSearch(
            users = CachedUserSearch(users::searchByName),
            dms = dms,
            selfUserId = { self },
            limit = limit,
        )
    }

    @Test
    fun empty_query_returns_empty_list() = runTest {
        val job = Job()
        val search = fixture(coroutineContext, job, userBase = listOf(user(1, "alice")))
        assertEquals(emptyList(), search.search(""))
        assertEquals(emptyList(), search.search("   "))
        job.cancel()
    }

    @Test
    fun case_insensitive_substring_match_on_username() = runTest {
        val job = Job()
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(user(1, "AliceWonderland"), user(2, "bob"), user(3, "Carol")),
        )
        val result = search.search("ali").map { it.id.value }
        assertEquals(listOf(1L), result)
        job.cancel()
    }

    @Test
    fun matches_against_global_name_too() = runTest {
        val job = Job()
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(user(1, "u1", globalName = "Alice Wonderland"), user(2, "u2", globalName = "Bob")),
        )
        val result = search.search("wonder").map { it.id.value }
        assertEquals(listOf(1L), result)
        job.cancel()
    }

    @Test
    fun starts_with_query_ranks_above_contains_match() = runTest {
        val job = Job()
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(user(1, "zalice"), user(2, "aliceB")),
        )
        val ids = search.search("ali").map { it.id.value }
        assertEquals(listOf(2L, 1L), ids)
        job.cancel()
    }

    @Test
    fun self_user_is_excluded() = runTest {
        val job = Job()
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(user(1, "alice"), user(2, "alicia")),
            self = UserId(1),
        )
        val ids = search.search("ali").map { it.id.value }
        assertEquals(listOf(2L), ids)
        job.cancel()
    }

    @Test
    fun bots_and_system_users_are_excluded() = runTest {
        val job = Job()
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(
                user(1, "alicebot", bot = true),
                user(2, "alicesys", system = true),
                user(3, "alice"),
            ),
        )
        val ids = search.search("ali").map { it.id.value }
        assertEquals(listOf(3L), ids)
        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dm_recipients_are_unioned_with_persisted_users() = runTest(UnconfinedTestDispatcher()) {
        val job = Job()
        val onlyInDm = user(99, "dmpal")
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(user(1, "alice")),
            dmRecipients = listOf(onlyInDm),
        )
        val ids = search.search("dmp").map { it.id.value }
        assertTrue(99L in ids)
        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun union_is_deduplicated_by_user_id() = runTest(UnconfinedTestDispatcher()) {
        val job = Job()
        val dup = user(42, "duplicate", globalName = "Same Person")
        val search = fixture(
            coroutineContext, job,
            userBase = listOf(dup),
            dmRecipients = listOf(dup),
        )
        val ids = search.search("dup").map { it.id.value }
        assertEquals(listOf(42L), ids)
        job.cancel()
    }

    @Test
    fun result_count_capped_by_limit() = runTest {
        val job = Job()
        val many = (1..40).map { user(it.toLong(), "alice$it") }
        val search = fixture(coroutineContext, job, userBase = many, limit = 5)
        val result = search.search("alice")
        assertEquals(5, result.size)
        job.cancel()
    }
}
