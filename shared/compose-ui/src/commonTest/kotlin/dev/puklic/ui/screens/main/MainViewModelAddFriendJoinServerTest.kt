package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.session.FriendInviter
import dev.puklic.session.ServerJoiner
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test

/**
 * Tests for issue #80 — add-friend + join-server dialog state machines on [MainViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelAddFriendJoinServerTest {

    @Test
    fun openAddFriend_sets_open_with_empty_query() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this, RecordingInviter(), RecordingJoiner())
        vm.addFriend.value.isOpen shouldBe false
        vm.openAddFriend()
        vm.addFriend.value.isOpen shouldBe true
        vm.addFriend.value.query shouldBe ""
    }

    @Test
    fun submitAddFriend_pomelo_handle_calls_inviter_with_null_discriminator() =
        runTest(UnconfinedTestDispatcher()) {
            val inviter = RecordingInviter(result = Result.success(Unit))
            val vm = newVm(this, inviter, RecordingJoiner())
            vm.openAddFriend()
            vm.updateAddFriendQuery("alice")
            vm.submitAddFriend()
            yield()
            inviter.calls shouldBe listOf("alice" to null)
            vm.addFriend.value.isSubmitting shouldBe false
            vm.addFriend.value.successMessage shouldBe MainViewModel.ADD_FRIEND_SUCCESS
        }

    @Test
    fun submitAddFriend_legacy_form_splits_on_hash() = runTest(UnconfinedTestDispatcher()) {
        val inviter = RecordingInviter(result = Result.success(Unit))
        val vm = newVm(this, inviter, RecordingJoiner())
        vm.openAddFriend()
        vm.updateAddFriendQuery("bob#1234")
        vm.submitAddFriend()
        yield()
        inviter.calls shouldBe listOf("bob" to "1234")
    }

    @Test
    fun submitAddFriend_strips_leading_at_sign() = runTest(UnconfinedTestDispatcher()) {
        val inviter = RecordingInviter(result = Result.success(Unit))
        val vm = newVm(this, inviter, RecordingJoiner())
        vm.openAddFriend()
        vm.updateAddFriendQuery("@carol")
        vm.submitAddFriend()
        yield()
        inviter.calls shouldBe listOf("carol" to null)
    }

    @Test
    fun submitAddFriend_empty_query_sets_error_without_calling_inviter() =
        runTest(UnconfinedTestDispatcher()) {
            val inviter = RecordingInviter()
            val vm = newVm(this, inviter, RecordingJoiner())
            vm.openAddFriend()
            vm.updateAddFriendQuery("   ")
            vm.submitAddFriend()
            yield()
            inviter.calls.isEmpty() shouldBe true
            vm.addFriend.value.errorMessage shouldBe MainViewModel.ADD_FRIEND_EMPTY
        }

    @Test
    fun submitAddFriend_failure_surfaces_error_message() = runTest(UnconfinedTestDispatcher()) {
        val inviter = RecordingInviter(result = Result.failure(RuntimeException("bad")))
        val vm = newVm(this, inviter, RecordingJoiner())
        vm.openAddFriend()
        vm.updateAddFriendQuery("alice")
        vm.submitAddFriend()
        yield()
        vm.addFriend.value.isSubmitting shouldBe false
        vm.addFriend.value.errorMessage shouldBe MainViewModel.ADD_FRIEND_FAILED
    }

    @Test
    fun submitJoinServer_strips_discord_gg_url_to_code() = runTest(UnconfinedTestDispatcher()) {
        val joiner = RecordingJoiner(result = Result.success(Unit))
        val vm = newVm(this, RecordingInviter(), joiner)
        vm.openJoinServer()
        vm.updateJoinServerQuery("https://discord.gg/abc123")
        vm.submitJoinServer()
        yield()
        joiner.calls shouldBe listOf("abc123")
        vm.joinServer.value.successMessage shouldBe MainViewModel.JOIN_SERVER_SUCCESS
    }

    @Test
    fun submitJoinServer_strips_discord_com_invite_url() = runTest(UnconfinedTestDispatcher()) {
        val joiner = RecordingJoiner(result = Result.success(Unit))
        val vm = newVm(this, RecordingInviter(), joiner)
        vm.openJoinServer()
        vm.updateJoinServerQuery("https://discord.com/invite/xyz")
        vm.submitJoinServer()
        yield()
        joiner.calls shouldBe listOf("xyz")
    }

    @Test
    fun submitJoinServer_accepts_bare_code() = runTest(UnconfinedTestDispatcher()) {
        val joiner = RecordingJoiner(result = Result.success(Unit))
        val vm = newVm(this, RecordingInviter(), joiner)
        vm.openJoinServer()
        vm.updateJoinServerQuery("plainCode")
        vm.submitJoinServer()
        yield()
        joiner.calls shouldBe listOf("plainCode")
    }

    @Test
    fun submitJoinServer_failure_surfaces_error_message() = runTest(UnconfinedTestDispatcher()) {
        val joiner = RecordingJoiner(result = Result.failure(RuntimeException("boom")))
        val vm = newVm(this, RecordingInviter(), joiner)
        vm.openJoinServer()
        vm.updateJoinServerQuery("abc")
        vm.submitJoinServer()
        yield()
        vm.joinServer.value.errorMessage shouldBe MainViewModel.JOIN_SERVER_FAILED
    }

    @Test
    fun submit_with_no_seam_wired_surfaces_unavailable() = runTest(UnconfinedTestDispatcher()) {
        val vm = MainViewModel(
            componentContext = newComponentContext(),
            externalScope = this,
            friendInviter = null,
            serverJoiner = null,
        )
        vm.openAddFriend()
        vm.updateAddFriendQuery("alice")
        vm.submitAddFriend()
        yield()
        vm.addFriend.value.errorMessage shouldBe MainViewModel.ADD_FRIEND_UNAVAILABLE

        vm.openJoinServer()
        vm.updateJoinServerQuery("abc")
        vm.submitJoinServer()
        yield()
        vm.joinServer.value.errorMessage shouldBe MainViewModel.JOIN_SERVER_UNAVAILABLE
    }

    @Test
    fun parseInviteCode_handles_all_known_shapes() {
        parseInviteCode("https://discord.gg/abc") shouldBe "abc"
        parseInviteCode("http://discord.gg/abc") shouldBe "abc"
        parseInviteCode("discord.gg/abc") shouldBe "abc"
        parseInviteCode("https://discord.com/invite/abc") shouldBe "abc"
        parseInviteCode("https://discordapp.com/invite/abc") shouldBe "abc"
        parseInviteCode("abc") shouldBe "abc"
        parseInviteCode(" abc ") shouldBe "abc"
        parseInviteCode("https://discord.gg/abc/") shouldBe "abc"
        parseInviteCode("https://discord.gg/abc?event=1") shouldBe "abc"
        parseInviteCode("") shouldBe ""
    }

    @Test
    fun parseAddFriendQuery_handles_known_shapes() {
        parseAddFriendQuery("alice") shouldBe ("alice" to null)
        parseAddFriendQuery("@alice") shouldBe ("alice" to null)
        parseAddFriendQuery("bob#1234") shouldBe ("bob" to "1234")
        parseAddFriendQuery(" bob#1234 ") shouldBe ("bob" to "1234")
        // Trailing hash → treated as pomelo handle (no valid discriminator).
        parseAddFriendQuery("bob#") shouldBe ("bob#" to null)
        // Non-digit discriminator → treated as pomelo handle.
        parseAddFriendQuery("bob#abcd") shouldBe ("bob#abcd" to null)
    }

    private fun newVm(
        scope: kotlinx.coroutines.test.TestScope,
        inviter: FriendInviter,
        joiner: ServerJoiner,
    ): MainViewModel = MainViewModel(
        componentContext = newComponentContext(),
        orchestrators = null,
        sessionTransport = null,
        externalScope = scope,
        friendInviter = inviter,
        serverJoiner = joiner,
    )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }
}

private class RecordingInviter(
    private val result: Result<Unit> = Result.success(Unit),
) : FriendInviter {
    val calls: MutableList<Pair<String, String?>> = mutableListOf()
    override suspend fun addFriend(username: String, discriminator: String?): Result<Unit> {
        calls += username to discriminator
        return result
    }
}

private class RecordingJoiner(
    private val result: Result<Unit> = Result.success(Unit),
) : ServerJoiner {
    val calls: MutableList<String> = mutableListOf()
    override suspend fun joinServer(code: String): Result<Unit> {
        calls += code
        return result
    }
}
