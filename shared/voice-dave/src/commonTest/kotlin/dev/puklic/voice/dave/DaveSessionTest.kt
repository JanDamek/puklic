package dev.puklic.voice.dave

import dev.puklic.voice.dave.gateway.DaveOp
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test

class DaveSessionTest {

    private fun newSession(
        sentBinary: MutableList<Pair<Int, ByteArray>> = mutableListOf(),
        sentJson: MutableList<Pair<Int, String>> = mutableListOf(),
        client: FakeMlsClient = FakeMlsClient(),
    ): Triple<DaveSession, MutableList<Pair<Int, ByteArray>>, MutableList<Pair<Int, String>>> {
        val s = DaveSession(
            mlsClient = client,
            channelId = "ch-1",
            userId = "user-1",
            sendBinary = { op, payload -> sentBinary += op to payload },
            sendJson = { op, json -> sentJson += op to json },
        )
        return Triple(s, sentBinary, sentJson)
    }

    @Test
    fun `init transitions to Initialized`() = runTest {
        val (s, _, _) = newSession()
        s.init()
        s.state.value shouldBe DaveSession.State.Initialized
    }

    @Test
    fun `PREPARE_TRANSITION with supported version sends READY_FOR_TRANSITION and stores transitionId`() = runTest {
        val (s, _, sentJson) = newSession()
        s.init()
        val body = buildJsonObject {
            put("protocol_version", JsonPrimitive(1))
            put("transition_id", JsonPrimitive(7))
        }
        s.handleJsonOp(DaveOp.PROTOCOL_PREPARE_TRANSITION, body)

        sentJson.size shouldBe 1
        sentJson[0].first shouldBe DaveOp.PROTOCOL_READY_FOR_TRANSITION
        // Parse the JSON to verify content (don't string-match — formatting is implementation-detail).
        val replied = Json.parseToJsonElement(sentJson[0].second)
        (replied as kotlinx.serialization.json.JsonObject)["transition_id"]
            ?.let { (it as JsonPrimitive).content } shouldBe "7"

        s.state.value.shouldBeInstanceOf<DaveSession.State.PreparingTransition>().transitionId shouldBe 7
    }

    @Test
    fun `PREPARE_TRANSITION with unsupported version transitions to Disabled`() = runTest {
        val (s, _, sentJson) = newSession()
        s.init()
        val body = buildJsonObject {
            put("protocol_version", JsonPrimitive(99))
            put("transition_id", JsonPrimitive(7))
        }
        s.handleJsonOp(DaveOp.PROTOCOL_PREPARE_TRANSITION, body)

        sentJson.isEmpty() shouldBe true
        s.state.value.shouldBeInstanceOf<DaveSession.State.Disabled>()
    }

    @Test
    fun `PREPARE_EPOCH sends KeyPackage as binary frame`() = runTest {
        val client = FakeMlsClient().also { it.keyPackageBytes = byteArrayOf(1, 2, 3, 4, 5) }
        val (s, sentBin, _) = newSession(client = client)
        s.init()
        val body = buildJsonObject { put("epoch", JsonPrimitive(42L)) }
        s.handleJsonOp(DaveOp.PROTOCOL_PREPARE_EPOCH, body)
        sentBin.size shouldBe 1
        sentBin[0].first shouldBe DaveOp.MLS_KEY_PACKAGE
        sentBin[0].second.toList() shouldBe listOf<Byte>(1, 2, 3, 4, 5)
    }

    @Test
    fun `EXECUTE_TRANSITION after Welcome transitions to Active with current epoch`() = runTest {
        val client = FakeMlsClient().also {
            it.welcomeGroupId = "group-x"
            it.epoch = 3L
        }
        val (s, _, _) = newSession(client = client)
        s.init()
        s.handleBinaryOp(DaveOp.MLS_WELCOME, byteArrayOf(0x01))
        s.handleJsonOp(DaveOp.PROTOCOL_EXECUTE_TRANSITION, buildJsonObject {})
        val state = s.state.value.shouldBeInstanceOf<DaveSession.State.Active>()
        state.currentEpoch shouldBe 3L
    }

    @Test
    fun `INVALID_COMMIT_WELCOME re-inits MLS and lands back in Initialized`() = runTest {
        val client = FakeMlsClient().also { it.welcomeGroupId = "group-x" }
        val (s, _, _) = newSession(client = client)
        s.init()
        s.handleBinaryOp(DaveOp.MLS_WELCOME, byteArrayOf(0x01))
        s.handleJsonOp(DaveOp.MLS_INVALID_COMMIT_WELCOME, buildJsonObject {})
        s.state.value shouldBe DaveSession.State.Initialized
        // groupId reset → deriveFrameKey now returns null
        s.deriveFrameKey(ssrc = 1, generation = 0) shouldBe null
    }

    @Test
    fun `WELCOME stores groupId and processCommit routed for proposals + announce`() = runTest {
        val client = FakeMlsClient().also { it.welcomeGroupId = "group-y" }
        val (s, _, _) = newSession(client = client)
        s.init()
        s.handleBinaryOp(DaveOp.MLS_WELCOME, byteArrayOf(0x10))
        client.processWelcomeCalledWith.toList() shouldBe listOf(listOf<Byte>(0x10))

        s.handleBinaryOp(DaveOp.MLS_PROPOSALS, byteArrayOf(0x20))
        s.handleBinaryOp(DaveOp.MLS_ANNOUNCE_COMMIT_TRANSITION, byteArrayOf(0x21))
        client.processCommitCalls.size shouldBe 2
        client.processCommitCalls[0].first shouldBe "group-y"
        client.processCommitCalls[1].first shouldBe "group-y"
    }

    @Test
    fun `deriveFrameKey returns null before group join`() = runTest {
        val (s, _, _) = newSession()
        s.init()
        s.deriveFrameKey(ssrc = 1234, generation = 0) shouldBe null
    }

    @Test
    fun `deriveFrameKey returns 32 bytes with DAVE label and SSRC+generation context`() = runTest {
        val client = FakeMlsClient().also {
            it.welcomeGroupId = "group-z"
            it.exporterReturns = ByteArray(32) { it.toByte() }
        }
        val (s, _, _) = newSession(client = client)
        s.init()
        s.handleBinaryOp(DaveOp.MLS_WELCOME, byteArrayOf(0xFF.toByte()))

        val key = s.deriveFrameKey(ssrc = 0x0A0B0C0D, generation = 0x00000007, length = 32)
        key shouldBe ByteArray(32) { it.toByte() }
        client.exportSecretCalls.size shouldBe 1
        val call = client.exportSecretCalls[0]
        call.label shouldBe "Discord Secure Frames v0"
        call.length shouldBe 32
        call.groupId shouldBe "group-z"
        call.context.toList() shouldBe listOf<Byte>(
            0x0A, 0x0B, 0x0C, 0x0D, // SSRC big-endian
            0x00, 0x00, 0x00, 0x07, // generation big-endian
        )
    }
}

/**
 * In-memory MlsClient used by DaveSessionTest. Records calls so the test can
 * assert on dispatch, and lets each test seed the return values it cares about.
 */
private class FakeMlsClient : MlsClient {

    var initCallCount = 0
    var keyPackageBytes: ByteArray = byteArrayOf(0x99.toByte())
    var welcomeGroupId: String = "default-group"
    var epoch: Long = 0L
    var exporterReturns: ByteArray = ByteArray(32)

    val processWelcomeCalledWith: MutableList<List<Byte>> = mutableListOf()
    val processCommitCalls: MutableList<Pair<String, List<Byte>>> = mutableListOf()
    val processExternalSenderCalls: MutableList<Pair<String, List<Byte>>> = mutableListOf()
    val exportSecretCalls: MutableList<ExportCall> = mutableListOf()

    override suspend fun init(userId: String): ByteArray { initCallCount++; return byteArrayOf(0x01) }
    override suspend fun generateKeyPackage(): ByteArray = keyPackageBytes
    override suspend fun createGroup(channelId: String): String = channelId
    override suspend fun processWelcome(welcomeBytes: ByteArray): String {
        processWelcomeCalledWith += welcomeBytes.toList()
        return welcomeGroupId
    }
    override suspend fun processCommit(groupId: String, handshakeBytes: ByteArray) {
        processCommitCalls += groupId to handshakeBytes.toList()
    }
    override suspend fun processExternalSender(channelId: String, externalSenderBytes: ByteArray) {
        processExternalSenderCalls += channelId to externalSenderBytes.toList()
    }
    override suspend fun currentEpoch(groupId: String): Long = epoch
    override suspend fun exportSecret(
        groupId: String,
        label: String,
        context: ByteArray,
        length: Int,
    ): ByteArray {
        exportSecretCalls += ExportCall(groupId, label, context, length)
        return exporterReturns.copyOf(length)
    }
    override suspend fun signaturePublicKey(): ByteArray = byteArrayOf(0x02)
    override fun close() {}

    data class ExportCall(val groupId: String, val label: String, val context: ByteArray, val length: Int)
}
