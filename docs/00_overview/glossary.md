# Glossary

Domain terms for Puklic + Discord. Update when introducing a new type / abstraction.

## Discord domain

| Term | Definition |
|---|---|
| **Guild** | A Discord "server" — a container for channels, roles, and members. ID = snowflake. |
| **Channel** | A text / voice / category / thread / forum channel inside a guild, or a DM. |
| **DM / Group DM** | Direct message between 2 / more users, without a guild. |
| **User** | A Discord account. Self-user = the logged-in user (`@me`). |
| **Member** | A user in the context of a guild (nickname, roles, joined_at). |
| **Message** | A single text message with attachments, embeds, and reactions. |
| **Embed** | A structured block below a message (link preview, bot output). |
| **Attachment** | A file attached to a message (image, video, file). |
| **Reaction** | An emoji reaction on a message, with an aggregated count + per-user list. |
| **Gateway** | The Discord WebSocket endpoint for real-time events. |
| **REST API** | The Discord HTTP API v10 for request/response operations. |
| **Snowflake** | A 64-bit ID with an encoded timestamp. |
| **Presence** | A user's online status (online / idle / dnd / offline). |
| **Typing indicator** | An ephemeral event "X is typing in channel Y". |
| **Voice state** | A user connected to a voice channel, with mute/deafen flags. |
| **DAVE** | Discord Audio/Video Encryption — the E2EE voice protocol. |

## Puklic domain

| Term | Definition |
|---|---|
| **Session** | The lifecycle of a single account's connection (token + gateway + state). |
| **RichTextDocument** | A parsed AST of a message, structured as `List<RichTextBlock>`. |
| **RichTextBlock** | A block-level element: paragraph / code block / quote. |
| **RichTextNode** | An inline element: text / emoji / mention / link / inline code. |
| **MessageRepository** | A layer over SQLite + RAM cache + API; exposes Flow streams. |
| **DiscordSession** | Our abstraction over the gateway + REST for one account. |
| **PlatformXxxService** | `expect` interface in `:shared:platform-api`, `actual` per platform. |
| **Hot/warm/cold cache** | Message cache layers, see ADR-0003. |

## Anti-glossary (terms we do NOT use in Puklic)

| Term | Why not |
|---|---|
| **Bot** | Puklic is not a bot, see CLAUDE.md scope. |
| **Self-bot** | Out of scope, prohibited. |
| **Plugin** | No plugin system (Phases 1–5). |
| **Activity / Stage / Boost** | Outside MVP scope, see product-vision.md anti-goals. |
