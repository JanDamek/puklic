package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Issue #23 — `MessageOrchestrator.sendWithAttachments` bypasses the outbound queue. */
class MessageOrchestratorAttachmentsTest {

    private val channel = ChannelId(11L)

    @Test
    fun sendWithAttachments_invokes_gateway_with_attachments_and_persists_response() = runTest {
        val orch = build()
        try {
            orch.gateway.sendResponse = { ch, content, _, _ ->
                Result.success(message(id = 1000L, channelId = ch, ts = 1L, content = content))
            }
            val att = PendingAttachment("a-0", "file.png", byteArrayOf(1, 2, 3), "image/png")

            val result = orch.orchestrator.sendWithAttachments(channel, "hello", listOf(att))

            result.isSuccess shouldBe true
            orch.gateway.sentCalls shouldHaveSize 1
            orch.gateway.sentAttachments[0].first().filename shouldBe "file.png"
            orch.storage.findById(MessageId(1000L))?.rawContent shouldBe "hello"
        } finally { orch.scope.cancel() }
    }

    @Test
    fun sendWithAttachments_does_not_enqueue_in_outbound_queue() = runTest {
        val orch = build()
        try {
            orch.gateway.sendResponse = { ch, content, _, _ ->
                Result.success(message(id = 2000L, channelId = ch, ts = 1L, content = content))
            }
            val att = PendingAttachment("a-0", "x", byteArrayOf(0), null)

            orch.orchestrator.sendWithAttachments(channel, "hi", listOf(att))

            orch.queue.observePending().first() shouldHaveSize 0
        } finally { orch.scope.cancel() }
    }

    @Test
    fun sendWithAttachments_propagates_gateway_failure() = runTest {
        val orch = build()
        try {
            orch.gateway.sendResponse = { _, _, _, _ -> Result.failure(IllegalStateException("upload failed")) }
            val att = PendingAttachment("a-0", "x", byteArrayOf(0), null)

            val result = orch.orchestrator.sendWithAttachments(channel, "hi", listOf(att))

            result.isFailure shouldBe true
        } finally { orch.scope.cancel() }
    }

    @Suppress("LongMethod")
    private fun TestScope.build(): Bundle {
        val gateway = FakeMessageGateway()
        val storage = FakeMessageStorage()
        val queue = FakeOutboundQueue()
        val users = FakeUserRepository()
        val events = FakeGatewayEventSource()
        val selfUserId = MutableStateFlow<dev.puklic.ids.UserId?>(null)
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val orch = MessageOrchestrator(
            sessionScope = sessionScope,
            gatewaySource = events,
            messageGateway = gateway,
            storage = storage,
            userStorage = users,
            outboundQueue = queue,
            nonceGenerator = { "nonce-1" },
            selfUserIdProvider = { selfUserId.value },
        )
        return Bundle(orch, gateway, storage, queue, sessionScope)
    }

    private data class Bundle(
        val orchestrator: MessageOrchestrator,
        val gateway: FakeMessageGateway,
        val storage: FakeMessageStorage,
        val queue: FakeOutboundQueue,
        val scope: CoroutineScope,
    )
}
