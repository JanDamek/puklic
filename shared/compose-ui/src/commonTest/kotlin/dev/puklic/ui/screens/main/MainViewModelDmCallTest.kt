package dev.puklic.ui.screens.main

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.voice.AudioDevice
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.IncomingCall
import dev.puklic.voice.IncomingVideoFrame
import dev.puklic.voice.VoiceBusyException
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import dev.puklic.voice.screenshare.NoOpScreenShareClient
import dev.puklic.voice.screenshare.ScreenShareClient
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test

/**
 * Behavioural tests for `MainViewModel.startDmCall` (issue #16 Slice 1). Verifies that the
 * call routes to `VoiceClient.connect(null, channelId)` and that `VoiceBusyException` is
 * translated into a snackbar message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelDmCallTest {

    @Test
    fun startDmCall_invokes_voice_client_connect_with_null_guild_id() = runTest(UnconfinedTestDispatcher()) {
        val fake = RecordingVoiceClient()
        val vm = newVm(this, fake)
        val dm = ChannelId(123L)

        vm.startDmCall(dm)

        fake.connectCalls.size shouldBe 1
        fake.connectCalls.first().first shouldBe null
        fake.connectCalls.first().second shouldBe dm
    }

    @Test
    fun startDmCall_voice_busy_emits_snackbar() = runTest(UnconfinedTestDispatcher()) {
        val fake = RecordingVoiceClient(throwBusy = true)
        val vm = newVm(this, fake)

        val message = async { withTimeout(2_000L) { vm.snackbarMessage.first() } }
        // Give the collector a chance to subscribe before the emission.
        yield()
        vm.startDmCall(ChannelId(123L))

        message.await() shouldBe "Leave current call first"
    }

    private fun newVm(
        scope: kotlinx.coroutines.test.TestScope,
        voiceClient: VoiceClient,
    ): MainViewModel = MainViewModel(
        componentContext = newComponentContext(),
        orchestrators = null,
        sessionTransport = null,
        externalScope = scope,
        voiceClient = voiceClient,
    )

    private fun newComponentContext(): DefaultComponentContext {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        return ctx
    }
}

private class RecordingVoiceClient(
    private val throwBusy: Boolean = false,
) : VoiceClient {
    val connectCalls: MutableList<Pair<GuildId?, ChannelId>> = mutableListOf()

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    override val screenShare: ScreenShareClient = NoOpScreenShareClient()

    private val _daveState = MutableStateFlow<DaveUiState>(DaveUiState.Off)
    override val daveState: StateFlow<DaveUiState> = _daveState.asStateFlow()

    private val _incomingVideo = MutableStateFlow<Map<Int, IncomingVideoFrame>>(emptyMap())
    override val incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>> = _incomingVideo.asStateFlow()

    private val _incomingCalls = MutableStateFlow<List<IncomingCall>>(emptyList())
    override val incomingCalls: StateFlow<List<IncomingCall>> = _incomingCalls.asStateFlow()

    override suspend fun acceptIncoming(channelId: ChannelId) { /* no-op */ }
    override suspend fun declineIncoming(channelId: ChannelId) { /* no-op */ }

    override suspend fun connect(guildId: GuildId?, channelId: ChannelId) {
        connectCalls.add(guildId to channelId)
        if (throwBusy) throw VoiceBusyException()
    }

    override suspend fun disconnect() { /* no-op */ }
    override fun setSelfMute(muted: Boolean) {}
    override fun setSelfDeaf(deaf: Boolean) {}
    override fun selectCaptureDevice(id: String) {}
    override fun selectPlaybackDevice(id: String) {}
}
