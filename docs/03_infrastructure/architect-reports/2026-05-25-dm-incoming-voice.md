# DM Incoming Voice (Issue #19) — Step 2 Design v2 (2026-05-25)

> **v2 marker** — Full conceptual rewrite per HARD RULE #2 (no temporary patches; v1 superseded entirely). Addresses 7 MAJOR + 3 minor + 5 nit findings from Step 3 critic review.
>
> Step 1 v2 wire shapes (5 sources): KB `agent://claude-mcp/puklic-issue-19-step1-v2`.

## 1. Goal + non-goals

**Goal:** Receive `CALL_CREATE` for DM, surface AlertDialog with caller name/avatar + Accept/Decline. Accept reuses `VoiceClient.connect(null, channelId)` (#16 Slice 1). Decline POSTs `/call/stop-ringing`. Caller cancel detected via `CALL_DELETE` or `CALL_UPDATE` removing self from `ringing[]`. Multiple concurrent incoming calls are queued (FIFO) — no silent drops.

**Non-goals (v1):**
- Group DM orchestration UI — `GroupDmChannel` domain GAP confirmed; `isGroup` stubbed false, follow-up issue filed
- Ring sound (visual only)
- Closed-app notification wake (OS-level)
- Op 13 Request Call Connect bootstrap for pre-existing active calls
- Distinguishing `unavailable=true` (voice region outage) from `unavailable=false` (call ended) — see §11

## 2. Module touch map

**Modified:**
- `shared/protocol-discord/.../PublicApi.kt` — mapDispatch + DiscordDomainEvent
- `shared/protocol-discord/.../dto/` — new CallCreateDto, CallUpdateDto, CallDeleteDto
- `shared/protocol-discord/.../rest/DiscordRestClient.kt` — +stopRinging (with 404/410 swallow)
- `shared/repositories/.../GatewayEventSource.kt` — mirror events
- `shared/repositories/.../MessageRepository.kt` — used for callerId message-author fallback (read-only)
- `shared/voice/.../PublicApi.kt` — +IncomingCall + incomingCalls (StateFlow<List>) + acceptIncoming + declineIncoming
- `shared/voice/src/jvmMain/.../DefaultVoiceClient.kt` — subscribe + atomic queue state
- `shared/voice/.../NoOpVoiceClient.kt` — stub additions
- `shared/compose-ui/.../components/voice/IncomingCallDialog.kt` (NEW)
- `shared/compose-ui/.../screens/main/MainScreen.kt` + `MainViewModel.kt` — wire dialog queue

**No new modules. No SQLDelight. No new dependencies.**

## 3. DTOs

```kotlin
@Serializable
internal data class CallCreateDto(
    @SerialName("channel_id") val channelId: String,
    @SerialName("message_id") val messageId: String? = null,
    val region: String? = null,
    val ringing: List<String> = emptyList(),
    @SerialName("voice_states") val voiceStates: List<VoiceStateUpdateDto> = emptyList(),
    val unavailable: Boolean = false,
)

@Serializable
internal data class CallUpdateDto(
    @SerialName("channel_id") val channelId: String,
    @SerialName("message_id") val messageId: String? = null,
    val region: String? = null,
    val ringing: List<String> = emptyList(),
)

@Serializable
internal data class CallDeleteDto(
    @SerialName("channel_id") val channelId: String,
    val unavailable: Boolean = false,
)

@Serializable
private data class StopRingingDto(val recipients: List<String>?)
```

## 3a. Dispatch event-string mapping (pinned)

`mapDispatch` in `shared/protocol-discord` MUST match exactly these `t` literals (confirmed by Step 1 v2 across 5 sources):

```kotlin
"CALL_CREATE" -> json.decodeFromJsonElement(CallCreateDto.serializer(), d)
"CALL_UPDATE" -> json.decodeFromJsonElement(CallUpdateDto.serializer(), d)
"CALL_DELETE" -> json.decodeFromJsonElement(CallDeleteDto.serializer(), d)
```

No fuzzy match, no alternate casings.

## 4. Domain events + callerId fallback chain

In `DiscordDomainEvent` + mirror `GatewayDomainEvent`:

```kotlin
public data class CallStarted(
    val channelId: ChannelId,
    val callerId: UserId?,              // resolved best-effort, see chain below
    val messageId: MessageId?,          // preserved so VoiceClient can resolve fallback
    val ringing: Set<UserId>,
    val region: String?,
) : DiscordDomainEvent

public data class CallRingingUpdated(
    val channelId: ChannelId,
    val ringing: Set<UserId>,
) : DiscordDomainEvent

public data class CallEnded(
    val channelId: ChannelId,
    val unavailable: Boolean,
) : DiscordDomainEvent
```

### callerId fallback chain (explicit)

When mapping CALL_CREATE → `CallStarted`, the protocol layer derives `callerId` synchronously where possible; the asynchronous fallback (message author lookup) runs in `DefaultVoiceClient` because it requires Repository / REST access and must not block the gateway dispatcher.

1. **First** — `voice_states.firstOrNull { it.userId != selfId }?.userId` (caller has already emitted VOICE_STATE_UPDATE).
2. **Second** — if (1) yields null AND `messageId != null` → resolve via `MessageRepository.findById(channelId, messageId)` (cache hit common because CALL_REQUEST system message is already in the channel feed). If not cached, fall back to REST `GET /channels/{channelId}/messages/{messageId}` and read `author.id`. The CALL_REQUEST system message author IS the caller by Discord semantics.
3. **Third** — if both (1) and (2) fail → leave `callerId = null`. UI MUST render a generic "Incoming call from this DM" without the word "Unknown".

The resolved `callerId` is then attached to `IncomingCall` before it is appended to the queue (§6).

## 5. Voice client API

```kotlin
public data class IncomingCall(
    val channelId: ChannelId,
    val callerId: UserId?,
    val isGroup: Boolean,
)

public interface VoiceClient {
    // ... existing
    /** Ordered FIFO queue of currently ringing incoming calls. Head = currently shown. */
    public val incomingCalls: StateFlow<List<IncomingCall>>
    public suspend fun acceptIncoming(channelId: ChannelId)
    public suspend fun declineIncoming(channelId: ChannelId)
}
```

No `ringDismissed` SharedFlow — removal is observable directly via `incomingCalls` reducing.

## 6. State machine (single-source-of-truth queue)

In `DefaultVoiceClient`:

```kotlin
private val _incomingCalls = MutableStateFlow<List<IncomingCall>>(emptyList())
public override val incomingCalls: StateFlow<List<IncomingCall>> = _incomingCalls.asStateFlow()

sessionScope.launch {
    gatewayEvents.collect { event ->
        when (event) {
            is CallStarted -> handleCallStarted(event)
            is CallRingingUpdated -> {
                val selfId = selfUserIdProvider() ?: return@collect
                if (selfId !in event.ringing) removeFromQueue(event.channelId)
            }
            is CallEnded -> removeFromQueue(event.channelId)
            else -> {}
        }
    }
}

private suspend fun handleCallStarted(event: CallStarted) {
    val selfId = selfUserIdProvider() ?: return
    if (selfId !in event.ringing) return  // self-initiated outgoing — ignore
    if (_incomingCalls.value.any { it.channelId == event.channelId }) return  // dedup
    val callerId = event.callerId
        ?: event.messageId?.let { resolveMessageAuthor(event.channelId, it) }
    val call = IncomingCall(event.channelId, callerId, isGroup = false)
    _incomingCalls.update { it + call }
}

private fun removeFromQueue(channelId: ChannelId) {
    _incomingCalls.update { list -> list.filterNot { it.channelId == channelId } }
}

override suspend fun acceptIncoming(channelId: ChannelId) {
    removeFromQueue(channelId)
    connect(guildId = null, channelId = channelId)  // reuses #16 Slice 1
}

override suspend fun declineIncoming(channelId: ChannelId) {
    val self = selfUserIdProvider() ?: return
    removeFromQueue(channelId)
    restClient.stopRinging(channelId, listOf(self))
}
```

All mutations go through `_incomingCalls.update { … }` → atomic, no ordering hazard between "added" and "dismissed" SharedFlows (MAJOR 6 fix).

`resolveMessageAuthor` returns `UserId?`, swallows exceptions (best-effort fallback, never throws into gateway pipeline).

## 7. REST stopRinging (already-gone tolerant)

```kotlin
public suspend fun stopRinging(channelId: ChannelId, recipients: List<UserId>) {
    try {
        httpClient.post("$BASE/channels/${channelId.value}/call/stop-ringing") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(StopRingingDto(recipients.map { it.value.toString() }))
        }
    } catch (e: ClientRequestException) {
        when (e.response.status.value) {
            404, 410 -> { /* call already ended / declined elsewhere — treat as success */ }
            else -> throw e
        }
    }
}
```

## 8. UI — queue + race-free Accept

`MainViewModel` exposes:

```kotlin
val currentIncomingCall: StateFlow<IncomingCall?> =
    voiceClient.incomingCalls
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
```

`MainScreen` renders `IncomingCallDialog` whenever `currentIncomingCall != null`. As VoiceClient pops the head (accept/decline/remote cancel), the next call auto-advances; no extra signaling required.

### IncomingCallDialog (race-free 30s timer)

```kotlin
@Composable
fun IncomingCallDialog(
    call: IncomingCall,
    callerName: String?,
    callerAvatarUrl: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val accepted = remember(call) { mutableStateOf(false) }

    LaunchedEffect(call, accepted.value) {
        if (!accepted.value) {
            delay(30_000)
            if (!accepted.value) onDecline()
        }
    }

    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(callerName ?: "Incoming call from this DM") },
        text = { /* 48dp PuklicAvatar + "{callerName} is calling..." */ },
        confirmButton = {
            Button(onClick = {
                accepted.value = true
                onAccept()
            }) { Text("Accept") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text("Decline") }
        },
    )
}
```

`accepted` guards both invocations of `delay(30_000)`: setting it true cancels the LaunchedEffect (key change re-launches; the re-launched body short-circuits on the outer `if`). The post-delay double check protects against the race where the delay finishes but `accepted` was flipped mid-recomposition.

Caller name/avatar resolved via `UserOrchestrator.observe(callerId)`; `callerId == null` → name shown as null → fallback title rendered.

## 9. Edge cases

- **Self-initiated outgoing call** (#16 Slice 1): Discord sends CALL_CREATE with self NOT in `ringing[]` → filter excludes.
- **Cancel mid-ring:** `CallRingingUpdated` removing self OR `CallEnded` → queue entry filtered out → if it was head, dialog dismisses; next queued call (if any) shows automatically.
- **Accept then remote hangup:** channel removed from queue on accept; subsequent `CallEnded` is a no-op (filterNot finds nothing); voice disconnect path handles.
- **Accept-vs-timer race:** `accepted` flag in dialog guarantees only one path (server connect OR stopRinging), never both.
- **Burst (multiple callers):** all queued; FIFO dismissal. Buffer/overflow non-issue because we use StateFlow, not SharedFlow.
- **Empty voice_states + present message_id:** message-author fallback resolves callerId.
- **Both callerId sources fail:** dialog shows generic title; Accept/Decline still functional (channelId is all that's needed for the server calls).
- **Dedup:** if the same `channelId` is already queued and a second CALL_CREATE arrives (unlikely but possible on reconnect), it is ignored.

## 10. Tests (Step 5)

- `CallCreateDtoTest` — deserialize sample payload (captured shape from KB)
- `DefaultVoiceClientIncomingTest`:
  - CallStarted with self in ringing → queue size 1, IncomingCall present
  - CallStarted with self NOT in ringing → queue empty
  - CallStarted with empty `voice_states` + present `message_id` → callerId derived from MessageRepository
  - CallStarted with empty `voice_states` + null `message_id` → callerId null, queue size 1
  - CallRingingUpdated removing self → queue empty
  - CallEnded active → queue empty
  - CallEnded inactive → silent (no exception, queue unchanged)
  - Duplicate CallStarted same channelId → queue size still 1
  - Burst of 3 distinct CallStarted → queue size 3, FIFO order preserved
  - acceptIncoming → head removed, `connect(null, channelId)` called once
  - declineIncoming → head removed, `restClient.stopRinging(channelId, [selfUserId])` called once
  - Accept-then-timer-race (simulate accepted=true then advance time) → no second server-side action
  - stopRinging 404 response → swallowed gracefully (no exception out of declineIncoming)
  - stopRinging 410 response → swallowed gracefully
  - stopRinging 500 response → propagates exception
- Compose UI test for IncomingCallDialog: skipped (no infra per #8)

## 11. Risks + known limitations

1. **Bootstrap missed CALL_CREATE** if call active before our gateway connect — Op 13 Request Call Connect deferred to follow-up issue.
2. **GroupDmChannel GAP** — `isGroup=false` stub; group recipient list UI deferred. Decline payload for group DMs uses `recipients=[self]` (see MINOR 8 inline note in impl).
3. **`selfUserIdProvider` wiring** — verify VoiceClient already has it (#16 Slice 1); if missing, inject via constructor.
4. **CallEnded `unavailable=true` semantics** — v1 treats `unavailable=true` (voice region outage, call temporarily unreachable) identically to `unavailable=false` (call ended): both pop the queue entry. Discord's semantic intent is distinct. Follow-up issue: surface "Call temporarily unavailable" toast separate from silent dismissal. Tracked, not blocking v1.
5. **30s auto-dismiss timer** matches observed Discord client behavior; revisit during impl if Discord changes.
6. **Message-author REST fallback** adds one HTTP round-trip when voice_states is empty AND message is not cached. Acceptable: rare path, runs in `sessionScope`, does not block gateway dispatcher.

## 12. Decisions locked from critic round

- Queue model: `StateFlow<List<IncomingCall>>` (option (b) from MAJOR 3). FIFO. Simpler than parallel dialogs.
- Single source of truth: drop `ringDismissed` SharedFlow; observable removal via queue reducer.
- Buffer policy: N/A (StateFlow conflates by identity; no overflow risk).
- 404/410 on stopRinging: success.
- Group DM decline body: `recipients=[self]` (NOT all recipients — that would cancel ring for everyone).

## 13. Critical files for impl

- `shared/voice/src/commonMain/kotlin/dev/puklic/voice/PublicApi.kt`
- `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/DefaultVoiceClient.kt`
- `shared/voice/src/commonMain/kotlin/dev/puklic/voice/NoOpVoiceClient.kt`
- `shared/protocol-discord/src/commonMain/kotlin/dev/puklic/protocol/discord/PublicApi.kt`
- `shared/protocol-discord/src/commonMain/kotlin/dev/puklic/protocol/discord/rest/DiscordRestClient.kt`
- `shared/protocol-discord/src/commonMain/kotlin/dev/puklic/protocol/discord/dto/CallDtos.kt` (NEW)
- `shared/repositories/src/commonMain/kotlin/dev/puklic/repositories/GatewayEventSource.kt`
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/components/voice/IncomingCallDialog.kt` (NEW)
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainScreen.kt`
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainViewModel.kt`
