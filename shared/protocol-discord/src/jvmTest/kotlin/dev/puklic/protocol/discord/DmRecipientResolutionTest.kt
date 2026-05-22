package dev.puklic.protocol.discord

import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.GatewayDispatchEvent
import dev.puklic.protocol.discord.gateway.GatewayFrameIn
import dev.puklic.protocol.discord.gateway.GatewayTransport
import dev.puklic.protocol.discord.gateway.GatewayTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * User-mode READY ships DM private_channels with only `recipient_ids`; the actual user records
 * live in a top-level `users` array. The bridge must join them and populate DmChannel.recipients
 * for downstream UI hydration. Live trace showed recipients=0 for every DM, which broke the DM
 * list header rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DmRecipientResolutionTest {

    @Test
    fun dm_recipient_ids_are_resolved_from_top_level_users_array() = runTest(UnconfinedTestDispatcher()) {
        val gw = TestGw()
        val bridgeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val bridge = DiscordGatewayBridge(gw.asPublicGateway(), bridgeScope)
        val collected = mutableListOf<DiscordDomainEvent>()
        val job = launch { bridge.events.collect { collected.add(it) } }

        val readyPayload = Json.parseToJsonElement(
            """
            {
              "session_id": "sess-DM-user-mode",
              "resume_gateway_url": "wss://gw.discord.gg",
              "user": {"id":"111","username":"me"},
              "guilds": [],
              "users": [
                {"id":"42","username":"alice","global_name":"Alice"},
                {"id":"43","username":"bob"},
                {"id":"44","username":"carol"}
              ],
              "private_channels": [
                {"id":"5001","type":1,"recipient_ids":["42"]},
                {"id":"5002","type":3,"recipient_ids":["43","44"]}
              ],
              "v": 10
            }
            """.trimIndent(),
        )
        gw.emit(GatewayDispatchEvent(type = "READY", sequence = 1, payload = readyPayload))

        withTimeout(2_000) {
            while (collected.count { it is DiscordDomainEvent.ChannelCreated } < 2) {
                kotlinx.coroutines.yield()
            }
        }
        job.cancel()
        bridgeScope.cancel()

        val dmEvents = collected.filterIsInstance<DiscordDomainEvent.ChannelCreated>()
            .mapNotNull { it.channel as? dev.puklic.domain.DmChannel }
        assertEquals(2, dmEvents.size)

        val dm1to1 = dmEvents.single { it.id.value == 5001L }
        assertEquals(1, dm1to1.recipients.size, "1:1 DM must have 1 resolved recipient")
        assertEquals("alice", dm1to1.recipients.single().username)

        val groupDm = dmEvents.single { it.id.value == 5002L }
        assertEquals(2, groupDm.recipients.size, "Group DM must have 2 resolved recipients")
        assertEquals(setOf("bob", "carol"), groupDm.recipients.map { it.username }.toSet())
    }

    @Test
    fun bot_mode_recipients_field_still_works_unchanged() = runTest(UnconfinedTestDispatcher()) {
        val gw = TestGw()
        val bridgeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val bridge = DiscordGatewayBridge(gw.asPublicGateway(), bridgeScope)
        val collected = mutableListOf<DiscordDomainEvent>()
        val job = launch { bridge.events.collect { collected.add(it) } }

        val readyPayload = Json.parseToJsonElement(
            """
            {
              "session_id": "sess-DM-bot",
              "user": {"id":"111","username":"me"},
              "guilds": [],
              "private_channels": [
                {"id":"6001","type":1,"recipients":[{"id":"77","username":"dave"}]}
              ],
              "v": 10
            }
            """.trimIndent(),
        )
        gw.emit(GatewayDispatchEvent(type = "READY", sequence = 1, payload = readyPayload))

        withTimeout(2_000) {
            while (collected.count { it is DiscordDomainEvent.ChannelCreated } < 1) {
                kotlinx.coroutines.yield()
            }
        }
        job.cancel()
        bridgeScope.cancel()

        val dm = collected.filterIsInstance<DiscordDomainEvent.ChannelCreated>()
            .single().channel as dev.puklic.domain.DmChannel
        assertEquals(1, dm.recipients.size)
        assertTrue(dm.recipients.single().username == "dave")
    }
}

/** Local copy of the test-gateway helper from DiscordGatewayBridgeTest. */
private class TestGw {
    private val transport = FakeBridgeTransport2()
    private val factory: GatewayTransportFactory = { transport }
    private val gw: GatewayConnection by lazy {
        GatewayConnection(scope = TestScope(), token = "ignored", transportFactory = factory)
    }
    fun asPublicGateway(): GatewayConnection = gw
    fun emit(event: GatewayDispatchEvent) {
        val field = GatewayConnection::class.java.getDeclaredField("_events")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(gw) as kotlinx.coroutines.flow.MutableSharedFlow<GatewayDispatchEvent>
        flow.tryEmit(event)
    }
}

private class FakeBridgeTransport2 : GatewayTransport {
    private val ch = Channel<GatewayFrameIn>(Channel.UNLIMITED)
    override val incoming: Flow<GatewayFrameIn> = ch.consumeAsFlow()
    override suspend fun sendText(text: String) {}
    override suspend fun close(code: Int, reason: String) { ch.close() }
}
