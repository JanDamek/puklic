# Architect Report: Unread / Read-State (Issue #91)

Date separators, NOVÉ divider, MESSAGE_ACK send, channel unread indicators.

## Headline
The read-state DATA foundation already shipped in issue #81: `ReadStateRepository`
(+ SQLDelight `read_state` table), READY `read_state` parsing (`extractReadStates` in
PublicApi.kt, tolerates `read_state` flat + `read_state.entries`), inbound `MESSAGE_ACK`
decode, and `ReadStateOrchestrator` exposing `byChannel: StateFlow<Map<ChannelId, ReadStateView>>`.
Limitation: `hasUnread = mentionCount > 0` (mention-only); plain unread not surfaced; and
`lastReadMessageId` is persisted but not exposed on `ReadStateView`.

## Remaining work (slices)

- **Slice 0 (data):** expose `lastReadMessageId: MessageId?` on `ReadStateView`; add pure
  `isChannelUnread(channelLastMessageId, lastReadMessageId)` to `UnreadAggregation.kt`
  (snowflake numeric ordering — exact).
- **Slice 1 (data):** MESSAGE_ACK SEND — `DiscordRestClient.ackMessage(channelId, messageId)`
  → `POST /channels/{cid}/messages/{mid}/ack` body `{"token":null}`; `SessionTransport.markChannelRead`;
  fire in `MainViewModel.selectChannel` + when newest message visible; de-dup via pure
  `shouldAck(target, lastAcked, lastRead)` (only ack when target id > both). Optimistic `onAck`.
- **Slice 2 (UI):** channel-row unread — `ChannelListItem` bold name + badge when unread/mention
  (badge already present; bold + drop dead `opacity` line). Wire `unreadCount`/`mentionCount` at
  the two guild-channel call sites in MainScreen (currently pass neither). New per-guild
  `channelUnread` flow in MainViewModel (mirror `dmUnread`).
- **Slice 3 (UI):** date separators — pure `isNewDay(current, previousOlder, zone)` +
  `czechLongDate(date)` ("21. března 2026", genitive months) in TimestampFormat.kt; `DateSeparator`
  composable inserted in MessageList item loop (reverseLayout → older sibling = msgs[idx+1]).
- **Slice 4 (UI):** NOVÉ divider — inject `lastReadMessageId` into MessageListViewModel, expose on
  `MessageListState.Loaded`; pure `firstUnreadMessageId(messagesOldestFirst, lastRead)`; `UnreadDivider`
  (red rule + right "NOVÉ" chip) above first unread. Snapshot once on channel open (anti-flicker).

Order: 0+1 together → 2 → 3 → 4.

## UX (approved style — Discord parity)
- (a) Date separator: centered muted `labelSmall` on a thin `outlineVariant` rule, Czech genitive
  month, no leading zero day.
- (b) NOVÉ divider: `error`-red 1dp rule full width + right-aligned red "NOVÉ" chip, above first unread.
- (c) Channel rows: read = muted regular; unread = bold/bright; mention = bold + red count Badge (9+ cap).

## Notes
- Snowflake `Long` id ordering for all unread math (no timestamps needed).
- Czech month genitive: ledna, února, března, dubna, května, června, července, srpna, září, října,
  listopadu, prosince. Rest of UI stays English (Phase 2 L10n).
- Pre-existing no-op `opacity` in ChannelListItem.kt to clean up.
