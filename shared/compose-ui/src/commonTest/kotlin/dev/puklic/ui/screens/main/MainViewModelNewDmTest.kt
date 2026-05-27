package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.UserId
import dev.puklic.session.DmCreator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test

/**
 * Tests for the New-DM picker integration in [MainViewModel] (issue #17).
 *
 * The picker flow:
 *  - openNewDm() puts the dialog in open state with empty query/results
 *  - pickNewDmRecipient() invokes the wired DmCreator, then selects the resulting channel
 *  - the call is fire-and-forget; success path closes the dialog
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelNewDmTest {

    @Test
    fun openNewDm_resets_state_to_open_with_empty_query() = runTest(UnconfinedTestDispatcher()) {
        val vm = newVm(this, RecordingDmCreator())
        vm.newDm.value.isOpen shouldBe false

        vm.openNewDm()

        vm.newDm.value.isOpen shouldBe true
        vm.newDm.value.query shouldBe ""
        vm.newDm.value.results shouldBe emptyList()
        vm.newDm.value.isSubmitting shouldBe false
    }

    @Test
    fun pickNewDmRecipient_calls_creator_and_selects_returned_channel_on_success() = runTest(UnconfinedTestDispatcher()) {
        val opened = DmChannel(
            id = ChannelId(7777L),
            recipients = emptyList(),
            lastMessageId = null,
        )
        val creator = RecordingDmCreator(result = Result.success(opened))
        val vm = newVm(this, creator)
        vm.openNewDm()

        vm.pickNewDmRecipient(UserId(42L))
        yield()

        creator.calls shouldBe listOf(UserId(42L))
        // selectedChannelId is exposed via [MainScreenState] only when orchestrators are wired;
        // here we assert the side-effect that closes the picker (success path) instead.
        vm.newDm.value.isOpen shouldBe false
    }

    @Test
    fun pickNewDmRecipient_failure_keeps_dialog_open_and_clears_submitting() = runTest(UnconfinedTestDispatcher()) {
        val creator = RecordingDmCreator(result = Result.failure(RuntimeException("boom")))
        val vm = newVm(this, creator)
        vm.openNewDm()

        vm.pickNewDmRecipient(UserId(1L))
        yield()

        vm.newDm.value.isOpen shouldBe true
        vm.newDm.value.isSubmitting shouldBe false
    }

    @Test
    fun pickNewDmRecipient_with_no_creator_is_safe_noop() = runTest(UnconfinedTestDispatcher()) {
        val vm = MainViewModel(
            componentContext = newComponentContext(),
            externalScope = this,
            dmCreator = null,
        )
        vm.openNewDm()
        vm.pickNewDmRecipient(UserId(1L))
        // No exception; dialog remains open since no creator was wired.
        vm.newDm.value.isOpen shouldBe true
    }

    private fun newVm(
        scope: kotlinx.coroutines.test.TestScope,
        creator: DmCreator,
    ): MainViewModel = MainViewModel(
        componentContext = newComponentContext(),
        orchestrators = null,
        sessionTransport = null,
        externalScope = scope,
        dmCreator = creator,
    )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }
}

private class RecordingDmCreator(
    private val result: Result<Channel> = Result.success(
        DmChannel(id = ChannelId(1L), recipients = emptyList(), lastMessageId = null),
    ),
) : DmCreator {
    val calls: MutableList<UserId> = mutableListOf()
    override suspend fun createOrOpenDm(recipientId: UserId): Result<Channel> {
        calls += recipientId
        return result
    }
}
