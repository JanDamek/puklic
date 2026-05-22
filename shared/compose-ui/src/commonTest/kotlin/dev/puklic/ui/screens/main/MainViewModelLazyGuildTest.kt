package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.session.GatewayLifecycleEvent
import dev.puklic.session.SessionTransport
import dev.puklic.session.TokenValidation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class SpyTransport : SessionTransport {
    val lazyCalls = mutableListOf<Pair<GuildId, List<ChannelId>>>()
    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(extraBufferCapacity = 4)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()
    override suspend fun validateToken(token: String): TokenValidation = TokenValidation.Unauthorized
    override suspend fun connectGateway(token: String) {}
    override suspend fun disconnectGateway() {}
    override suspend fun lazyRequestGuild(guildId: GuildId, channelIds: List<ChannelId>) {
        lazyCalls += guildId to channelIds
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLazyGuildTest {

    @Test
    fun selectChannel_triggers_op14_with_single_channel() = runTest(UnconfinedTestDispatcher()) {
        val spy = SpyTransport()
        val vm = newVm(spy, this)

        // No guild selected → selectChannel must be a no-op for OP 14.
        vm.selectChannel(ChannelId(7L))
        assertTrue(spy.lazyCalls.isEmpty(), "selectChannel must require a selected guild")

        // selectGuild has no orchestrators wired so its bootstrap path early-returns; we still
        // expose the selected guild via the StateFlow so the subsequent selectChannel can fire.
        vm.selectGuild(GuildId(42L))
        vm.selectChannel(ChannelId(7L))
        assertEquals(1, spy.lazyCalls.size)
        assertEquals(GuildId(42L), spy.lazyCalls.first().first)
        assertEquals(listOf(ChannelId(7L)), spy.lazyCalls.first().second)
    }

    @Test
    fun selectChannel_without_session_transport_is_noop() = runTest(UnconfinedTestDispatcher()) {
        val vm = MainViewModel(
            componentContext = newComponentContext(),
            orchestrators = null,
            sessionTransport = null,
            externalScope = this,
        )
        // Should not throw.
        vm.selectGuild(GuildId(42L))
        vm.selectChannel(ChannelId(7L))
    }

    private fun newVm(transport: SessionTransport, scope: TestScope): MainViewModel =
        MainViewModel(
            componentContext = newComponentContext(),
            orchestrators = null,
            sessionTransport = transport,
            externalScope = scope,
        )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }
}
