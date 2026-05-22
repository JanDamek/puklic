package dev.puklic.protocol.discord

import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.GatewayDispatchEvent
import dev.puklic.protocol.discord.gateway.GatewayFrameIn
import dev.puklic.protocol.discord.gateway.GatewayTransport
import dev.puklic.protocol.discord.gateway.GatewayTransportFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordGatewayBridgeTest {

    @Test
    fun ready_with_inline_guilds_emits_ready_plus_guild_created_per_guild() = runTest(UnconfinedTestDispatcher()) {
        val gw = TestGateway()
        val bridgeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val bridge = DiscordGatewayBridge(gw.asPublicGateway(), bridgeScope)
        val collected = mutableListOf<DiscordDomainEvent>()
        val job = launch { bridge.events.collect { collected.add(it) } }

        val readyPayload = Json.parseToJsonElement(
            """
            {
              "session_id": "sess-42",
              "resume_gateway_url": "wss://gw.discord.gg",
              "user": {"id":"111","username":"me","discriminator":"0001"},
              "guilds": [
                {"id":"1","name":"Alpha","owner_id":"111","features":[]},
                {"id":"2","name":"Beta","owner_id":"111","features":[]},
                {"id":"3","unavailable":true}
              ],
              "v": 10
            }
            """.trimIndent(),
        )
        gw.emit(GatewayDispatchEvent(type = "READY", sequence = 1, payload = readyPayload))

        withTimeout(2_000) {
            while (collected.count { it is DiscordDomainEvent.GuildCreated } < 2) {
                kotlinx.coroutines.yield()
            }
        }
        job.cancel()
        bridgeScope.cancel()

        val guildEvents = collected.filterIsInstance<DiscordDomainEvent.GuildCreated>()
        assertEquals(2, guildEvents.size, "unavailable stubs must be skipped, full guilds emitted")
        assertEquals(setOf("Alpha", "Beta"), guildEvents.map { it.guild.name }.toSet())
        assertTrue(collected.any { it is DiscordDomainEvent.Ready }, "Ready event must also be emitted")
    }

    @Test
    fun guild_create_with_channels_emits_guild_plus_channels() = runTest(UnconfinedTestDispatcher()) {
        val gw = TestGateway()
        val bridgeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val bridge = DiscordGatewayBridge(gw.asPublicGateway(), bridgeScope)
        val collected = mutableListOf<DiscordDomainEvent>()
        val job = launch { bridge.events.collect { collected.add(it) } }

        val guildPayload = Json.parseToJsonElement(
            """
            {
              "id": "100",
              "name": "MyGuild",
              "owner_id": "5",
              "features": [],
              "channels": [
                {"id":"201","name":"general","type":0,"position":0},
                {"id":"202","name":"random","type":0,"position":1}
              ]
            }
            """.trimIndent(),
        )
        gw.emit(GatewayDispatchEvent(type = "GUILD_CREATE", sequence = 2, payload = guildPayload))

        withTimeout(2_000) {
            while (collected.count { it is DiscordDomainEvent.ChannelCreated } < 2) {
                kotlinx.coroutines.yield()
            }
        }
        job.cancel()
        bridgeScope.cancel()

        assertTrue(collected.any { it is DiscordDomainEvent.GuildCreated })
        val channelEvents = collected.filterIsInstance<DiscordDomainEvent.ChannelCreated>()
        assertEquals(2, channelEvents.size)
    }
}

/** Minimal in-process Gateway that lets a test push raw [GatewayDispatchEvent]s. */
private class TestGateway {
    private val transport = FakeBridgeTransport()
    private val factory: GatewayTransportFactory = { transport }

    private val gw: GatewayConnection by lazy {
        GatewayConnection(
            scope = TestScope(),
            token = "ignored",
            transportFactory = factory,
        )
    }

    fun asPublicGateway(): GatewayConnection = gw

    fun emit(event: GatewayDispatchEvent) {
        // The bridge subscribes to `gateway.events` — we push into the underlying SharedFlow by
        // exposing it via reflection-free injection through the public test helper below.
        // To avoid reflection, we use the dispatch shortcut: emit a synthetic DISPATCH frame and
        // let the GatewayConnection's text handler decode + republish. Simpler: bypass and feed
        // the SharedFlow directly via a dispatch helper.
        gatewayEmit(gw, event)
    }
}

/** Test-only helper: re-emits an event onto the gateway's public SharedFlow via [tryEmit]. */
private fun gatewayEmit(gw: GatewayConnection, event: GatewayDispatchEvent) {
    val field = GatewayConnection::class.java.getDeclaredField("_events")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(gw) as kotlinx.coroutines.flow.MutableSharedFlow<GatewayDispatchEvent>
    flow.tryEmit(event)
}

private class FakeBridgeTransport : GatewayTransport {
    private val ch = Channel<GatewayFrameIn>(Channel.UNLIMITED)
    override val incoming: Flow<GatewayFrameIn> = ch.consumeAsFlow()
    override suspend fun sendText(text: String) {}
    override suspend fun close(code: Int, reason: String) { ch.close() }
}
