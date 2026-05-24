# DM 1:1 Voice Call — Outgoing (Slice 1) — Architect Report v2 (2026-05-24, issue #16)

> Revised after Step 3 critic. v1 → v2 changes:
> - F1: defensive `toLongOrNull()?.takeIf { it != 0L }?.let(::GuildId)` at PublicApi.kt:348 (matches existing :332 idiom)
> - F2: collision behavior — throw `VoiceBusyException` when state != Idle/Failed; UI shows snackbar "Leave current call first"
> - F3: correlation token (sequence number captured before Op 4 send) on VoiceServerUpdate filter
> - F4: explicit `withTimeoutOrNull(30.seconds)` → `Failed(reason="No answer", recoverable=true)`
> - F5: Add intermediate **Ringing** state — Op 4 ACK'd via VOICE_STATE_UPDATE but no VOICE_SERVER_UPDATE yet. Wait up to 30s in Ringing, then Failed.
> - F6: read MessagePane header composition + specify Row layout for phone icon
> - F7: Cancel during Connecting/Ringing sends revocation Op 4 with `channel_id=null` BEFORE local disconnect
> - F8: visual ringback animation (3-dot pulsing "Calling @Recipient...") in v1; audio ringback tone deferred to v2 follow-up
> - F9-11: extended test coverage + group DM disabled-with-tooltip

## Summary

Slice 1 implements **outgoing DM voice** from Puklic by making `guildId` nullable across the voice handshake stack. Op 4 path already accepts `guild_id=null`; only blockers are non-null types in `VoiceClient.connect`, `runConnect` filter, and `VoiceServerUpdateDto`. New **Ringing** intermediate state handles Discord's likely "VOICE_SERVER_UPDATE only after recipient picks up" behavior. 30s timeout in both Ringing → Failed and Connecting → Failed. Concurrent-call collision throws `VoiceBusyException`. Cancel mid-handshake sends revocation Op 4. UI: phone icon in DM header (4 states: Idle/Ringing/Connecting/Connected). **Incoming ring UI** deferred to Slice 2 pending empirical CALL_CREATE opcode capture.

## 1. Goal & non-goals

**Goal (Slice 1):** Outgoing DM 1:1 voice from Puklic; recipient accepts via their official Discord client.

**Non-goals (defer to Slice 2 / separate issues):**
- Incoming ring UI (CALL_CREATE opcode unconfirmed)
- Group DM (type 3) voice — domain not modeled
- DM screenshare/video
- REST `POST /channels/{id}/call/ring`

## 2. Module touch map

**Modified:**
- `shared/voice/src/commonMain/kotlin/dev/puklic/voice/PublicApi.kt:112` — `connect(guildId: GuildId?, channelId)`
- `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/DefaultVoiceClient.kt:164-205` — runConnect accepts nullable guildId; filters use null-safe equality
- `shared/protocol-discord/src/commonMain/kotlin/dev/puklic/protocol/discord/dto/VoiceDto.kt:33` — `guildId: String? = null`
- `shared/protocol-discord/src/commonMain/kotlin/dev/puklic/protocol/discord/PublicApi.kt:348` — VoiceServerUpdated holds `GuildId?`
- `shared/repositories/src/commonMain/kotlin/dev/puklic/repositories/GatewayEventSource.kt` — domain event nullable
- `shared/compose-ui/.../MainScreen.kt:118-122` — phone icon in DM header
- `shared/compose-ui/.../MainViewModel.kt` — `startDmCall(channelId)`
- `NoOpVoiceClient` — nullable signature

No new modules, no SQLDelight, no new gateway ops.

## 3. UI design

| voiceState | DM header |
|---|---|
| Idle | `[@Recipient] ............ [📞 Call]` |
| Connecting (this DM) | `[@Recipient] Calling @Recipient... [✖ Cancel]` |
| Connected (this DM) | `[@Recipient] In call with @Recipient` + existing VoiceDock |

Icon: `Icons.Filled.Call` Material. Click → `viewModel.startDmCall(channelId)`. Reuses VoiceDock — no new sidebar widget.

## 4. Nullable plumbing

```kotlin
public suspend fun connect(guildId: GuildId?, channelId: ChannelId)

mainGateway.sendVoiceStateUpdate(guildId, channelId, selfMute = false, selfDeaf = false)

val state = mainGateway.voiceStateUpdates
    .filterIsInstance<VoiceStateUpdate>()
    .first { it.userId == selfId && it.guildId == guildId && it.channelId == channelId }

val server = mainGateway.voiceServerUpdates
    .first { it.guildId == guildId && !it.endpoint.isNullOrBlank() }
```

`null == null` is correct Kotlin semantics; filter shape preserved.

## 5. DTO fix

```kotlin
@Serializable
internal data class VoiceServerUpdateDto(
    val token: String,
    @SerialName("guild_id") val guildId: String? = null,
    val endpoint: String? = null,
)
```

PublicApi.mapDispatch builds `GuildId?` via `guildId?.let(::GuildId)` — handles null/omitted/present.

## 6. Tests

- `DefaultVoiceClientDmTest` (jvmTest) — connect(null, dm) sends Op 4 guild_id=null, handshake completes
- `VoiceDtoTest` — deserialize VOICE_SERVER_UPDATE with `"guild_id": null`, missing, present
- `MainViewModelDmCallTest` (commonTest) — `startDmCall(id)` invokes voiceClient.connect(null, id)

## 7. Risks (v2 revised per critic)

1. **Discord may reject Op 4 guild_id=null for non-friends** → surfaces as Failed; Slice 2 adds `POST /call/ring`
2. **VOICE_SERVER_UPDATE guildId variants** — nullable default + defensive `toLongOrNull()?.takeIf { it != 0L }` (F1)
3. **Caller-only POV** until Slice 2
4. **Group DM type 3** — phone icon visible-but-disabled with tooltip (F11)
5. **State machine collision** — `VoiceBusyException` if state != Idle/Failed; UI snackbar (F2)
6. **VOICE_SERVER_UPDATE timing unverified** — Discord may delay until recipient picks up. Mitigated by Ringing intermediate state with 30s timeout (F4+F5)
7. **Cancel orphans server-side state** — Cancel during Connecting/Ringing sends revocation Op 4 with channel_id=null before local cleanup (F7)

## 8. v2 design additions (response to Step 3 critic)

### 8.1 Ringing intermediate state (F5)

```kotlin
sealed class VoiceState {
    object Idle : VoiceState()
    data class Connecting(val channelId: ChannelId, val guildId: GuildId?) : VoiceState()
    data class Ringing(val channelId: ChannelId, val guildId: GuildId?) : VoiceState()  // NEW v2
    data class Connected(val channelId: ChannelId, val guildId: GuildId?, ...) : VoiceState()
    data class Failed(val reason: String, val recoverable: Boolean) : VoiceState()
}
```

State machine:
1. Idle → `connect(null, dmId)` → sends Op 4 → **Connecting**
2. Connecting → VOICE_STATE_UPDATE received (recipient client acked) → **Ringing** (for DMs only)
3. Ringing → VOICE_SERVER_UPDATE received (recipient joined) → handshake → **Connected**
4. Ringing 30s timeout → **Failed("No answer", recoverable=true)**
5. Connecting 10s timeout (no VOICE_STATE_UPDATE) → **Failed("Couldn't reach recipient")**

For guild channels: skip Ringing (server always available) → Connecting → Connected directly.

### 8.2 PublicApi.kt:348 defensive mapping (F1)

```kotlin
"VOICE_SERVER_UPDATE" -> {
    val dto = json.decodeFromJsonElement<VoiceServerUpdateDto>(payload)
    val guildId = dto.guildId
        ?.takeUnless { it.isBlank() }
        ?.toLongOrNull()
        ?.takeIf { it != 0L }
        ?.let(::GuildId)
    listOf(DiscordDomainEvent.VoiceServerUpdated(guildId = guildId, token = dto.token, endpoint = dto.endpoint))
}
```

Matches existing :332 defensive idiom.

### 8.3 VoiceBusyException (F2)

```kotlin
class VoiceBusyException : Exception("Already in a voice call")

public suspend fun connect(guildId: GuildId?, channelId: ChannelId) {
    if (state.value !is VoiceState.Idle && state.value !is VoiceState.Failed) {
        throw VoiceBusyException()
    }
    // ... rest of connect
}
```

`MainViewModel.startDmCall` catches → emits snackbar "Leave current call first".

### 8.4 Timeout + correlation token (F3 + F4)

```kotlin
private suspend fun runConnect(guildId: GuildId?, channelId: ChannelId, selfId: UserId) {
    val correlationSeq = sequenceCounter.incrementAndGet()
    mainGateway.sendVoiceStateUpdate(guildId, channelId, false, false)
    
    val stateUpdate = withTimeoutOrNull(10_000L) {
        mainGateway.voiceStateUpdates
            .filterIsInstance<VoiceStateUpdate>()
            .first { it.userId == selfId && it.guildId == guildId && it.channelId == channelId }
    } ?: return failHandshake("Couldn't reach recipient")
    
    if (guildId == null) _state.value = VoiceState.Ringing(channelId, guildId)
    
    val serverTimeout = if (guildId == null) 30_000L else 10_000L
    val serverUpdate = withTimeoutOrNull(serverTimeout) {
        mainGateway.voiceServerUpdates
            .first { it.guildId == guildId && !it.endpoint.isNullOrBlank() && it.afterSeq(correlationSeq) }
    } ?: return failHandshake(if (guildId == null) "No answer" else "Server timeout")
    
    // rest of handshake (DAVE, UDP, RTP)
}
```

### 8.5 Cancel mid-handshake revocation (F7)

```kotlin
public suspend fun disconnect() {
    when (state.value) {
        is VoiceState.Connecting, is VoiceState.Ringing -> {
            runCatching {
                mainGateway.sendVoiceStateUpdate(activeGuildId, null, false, false)  // channel_id=null = revoke
            }
        }
        else -> {}
    }
    // existing disconnect path
}
```

### 8.6 Header phone icon — actual layout (F6)

Step 6 impl reads MessagePane header Row; expected:
```kotlin
Row(verticalAlignment = CenterVertically, modifier = Modifier.fillMaxWidth().padding(...)) {
    Column(modifier = Modifier.weight(1f)) {
        Text(displayName)
        if (channel is GuildTextChannel) topic?.let { Text(it, style = bodySmall) }
    }
    if (channel is DmChannel) {
        IconButton(onClick = { vm.startDmCall(channel.id) }) {
            Icon(Icons.Filled.Call, "Call ${recipient.displayName}")
        }
    }
}
```

### 8.7 Ringback visual (F8)

During Ringing in DM header: `Text("Calling @Recipient${dots}")` where `dots` cycles `""/"."/".."/"..." ` via `LaunchedEffect + delay(500ms)`. **No audio ringback v1** — too easy to forget mute; user gets visual + 30s timeout. Audio = v2 follow-up.

### 8.8 Group DM (F11)

```kotlin
is GroupDmChannel -> {
    IconButton(onClick = {}, enabled = false) {
        Icon(Icons.Filled.Call, "Group DM voice not yet supported")
    }
}
```

Note: GroupDmChannel domain doesn't yet exist (Step 1 gap). Step 6 impl either adds stub OR guards `is DmChannel` only (group DMs invisible). Decide in impl.

### 8.9 Extended test coverage (F9)

- `active voice in guild X + startDmCall(dmY)` → `VoiceBusyException` thrown; UI snackbar emitted
- `VOICE_SERVER_UPDATE never arrives within 30s during Ringing` → `Failed("No answer")`
- `VOICE_STATE_UPDATE never arrives within 10s during Connecting` → `Failed("Couldn't reach recipient")`
- `Cancel during Ringing` → sends Op 4 with channel_id=null, cleanup verified, state to Idle
- `VoiceServerUpdateDto guild_id: ""` → parses as null
- `VoiceServerUpdateDto guild_id: "0"` → parses as null (DM marker)
- `Correlation seq mismatch` → ignored (filters stale VoiceServerUpdate)
