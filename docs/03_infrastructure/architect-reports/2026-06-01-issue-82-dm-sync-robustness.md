# Issue #82 — DM sync robustness (heartbeat + auto-reconnect/RESUME)

Date: 2026-06-01

## Symptom

DMs load slowly, sometimes never receive new messages after the client has been running
for a while. Sometimes instant, sometimes silent. Symptom worsens with session age.

## Root-cause audit (Step 1)

Code audit of `:shared:protocol-discord` `gateway/GatewayConnection.kt` and
`:shared:session` `adapter/SessionTransportImpl.kt`:

### 1. No client heartbeat loop — CRITICAL

`GatewayConnection.handleText` only sends a heartbeat (OP 1) when the server requests
one via `Opcode.HEARTBEAT`. The Discord gateway requires the **client** to send OP 1 every
`heartbeat_interval` ms (typically 41.25 s) from receipt of HELLO. Without it, the gateway
silently stops dispatching events; the WebSocket may stay open at the TCP/TLS layer for
several minutes before close 4000 / `SESSION_TIMEOUT` arrives. Result: no MESSAGE_CREATE
events delivered, app appears "stuck on old data".

### 2. No auto-reconnect / RESUME loop — CRITICAL

`runLoop` collects frames until the socket closes, then exits. `handleClose` transitions
state to `Resuming(sessionId, seq)` for the resumable close codes — but **nothing acts on
`Resuming`**. The session sits in `Resuming` state forever. No new socket is opened, no
RESUME frame is sent, no events arrive.

### 3. Resume URL not used

Even on the path through `sendIdentify` that builds an OP 6 RESUME payload, the next
connect would re-open `wss://gateway.discord.gg/...` instead of `resumeGatewayUrl` from
the previous READY. Per Discord docs this works in practice but is non-compliant and
can fail.

### 4. INVALID_SESSION recovery

When server sends OP 9 INVALID_SESSION, `handleText` clears `sessionId`/`sequence` but
does not initiate a fresh IDENTIFY — and again, the outer loop is the place that would
do that, but it does not retry.

### 5. MESSAGE_CREATE routing for DMs — OK

`DiscordGatewayBridge.mapDispatch` handles MESSAGE_CREATE without any guild_id guard.
DM/Group-DM messages flow through cleanly. NOT a bug source.

### 6. DM channel discovery — OK

READY bootstrap parses `private_channels` and emits ChannelCreated events; runtime DM
opens come in via CHANNEL_CREATE (handled). NOT a bug source — provided gateway is alive
(which #1+#2 break).

### 7. REST history prefetch — OUT OF SCOPE for #82

`DiscordRestClient.getMessages` exists and is called from the message loader path;
first-open of a DM does pre-fetch history. NOT the cause of the "live updates missing"
symptom.

## Decision — fixes in scope of #82

Implement #1, #2, #3, #4 — all four are aspects of the same conceptual fix: a proper
gateway lifecycle supervisor with heartbeat, ACK tracking, and reconnect-with-RESUME.

Per HARD RULE #2 (NEVER TEMPORARY): the fix is a complete rewrite of the lifecycle,
not a patch.

## Design

`GatewayConnection.runLoop` becomes a `while (isActive)` supervisor:

1. Pick URL — `resumeGatewayUrl ?: GATEWAY_URL_DEFAULT`
2. Open transport, drive HELLO → IDENTIFY (or RESUME) → READY.
3. On HELLO, launch a child coroutine that sends OP 1 every `heartbeat_interval` ms with
   a small jitter (per Discord docs the first beat should be `interval * rand()`).
   Track `lastHeartbeatSent` and `lastAckReceived`. If next beat tick fires before ACK
   was received → "zombied connection" → `transport.close(4000, "zombied")` to trigger
   reconnect.
4. On socket close (any reason except `TokenInvalid` and explicit `disconnect()`):
   - resumable codes (`INVALID_SEQ`, `SESSION_TIMEOUT`, `RATE_LIMITED`, `UNKNOWN_ERROR`,
     and the abnormal-close 1006) → keep `sessionId` + `sequence`, set state Resuming,
     reconnect to `resumeGatewayUrl`, send OP 6 RESUME after HELLO.
   - non-resumable codes (`AUTH_FAILED`) → final state TokenInvalid, exit loop.
   - any other close → drop `sessionId`/`sequence`, state Disconnected → Connecting,
     reconnect via default URL with fresh IDENTIFY.
5. On OP 9 INVALID_SESSION: clear session state, close current socket with 1000, the
   outer loop reconnects with a fresh IDENTIFY (per spec: random 1-5 s wait).
6. On OP 7 RECONNECT: same as resumable close — close with 1000, outer loop resumes.
7. Backoff between reconnect attempts: 1, 2, 4, 8, 16, 30 s capped — clamps thundering
   herd if Discord is in incident.
8. `disconnect()` sets a `manualStop` flag → loop exits cleanly without reconnect.

State exposed to subscribers remains the existing `GatewayState` sealed type. The
`SessionTransportImpl` lifecycle mapping already turns Resuming → `Reconnecting`, so
the UI lifecycle indicator works without changes.

## Tests (Step 5 RED → Step 6 GREEN)

New tests in `GatewayConnectionTest.kt`:

- `client_sends_periodic_heartbeats_after_hello` — drive HELLO with 50 ms interval,
  observe `transport.sent` accumulating OP 1 frames over virtual time.
- `missed_heartbeat_ack_closes_connection_with_4000` — drive HELLO + IDENTIFY + READY,
  swallow ACKs, observe close call with 4000.
- `resumable_close_reconnects_with_resume_url_and_op6` — drive READY, then close 4009,
  observe second transport opened with `resume_gateway_url` and the first frame sent
  after HELLO is OP 6 RESUME (not OP 2 IDENTIFY).
- `invalid_session_clears_state_and_reidentifies` — OP 9, observe state cleared and
  next connect uses OP 2 IDENTIFY.
- `auth_failed_does_not_reconnect` — close 4004, observe loop exits, no reconnect.
- `manual_disconnect_does_not_reconnect` — call disconnect() after READY, no further
  connects.

## Acceptance

- Existing tests still pass (back-compat: server-requested heartbeat, identify shape,
  OP 37 bulk, OP 4 voice state, 4004 → TokenInvalid, 4009 → Resuming state).
- New tests above pass.
- `./gradlew :shared:protocol-discord:jvmTest :shared:session:jvmTest :shared:repositories:jvmTest` green.
