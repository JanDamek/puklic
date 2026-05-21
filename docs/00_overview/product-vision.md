# Puklic — Product Vision

## Tagline

> Puklic is a lightweight Kotlin Multiplatform desktop chat client focused on native UI, low memory usage, coroutine-first architecture, and first-class Linux Wayland support.

## Problem

The official Discord client is an Electron application consuming 400–800 MB of RAM at idle, with repeated UI freezes, poor Wayland support, and a slow cold start. On Linux, long-standing bugs have gone unresolved for years (screenshare on Wayland, notifications, tray, audio routing).

Existing alternatives (Ripcord — proprietary and abandoned, Abaddon — alpha, Webcord — still Electron) either stagnate or don't address the core problem.

## Target user

- **Linux power user** running Wayland (GNOME/KDE/Sway/Hyprland) who wants a stable client with a low RAM footprint
- **Cross-platform user** who wants the same client on Linux, Android, and iOS
- **Privacy-conscious user** who doesn't want Electron running all day

Secondarily: macOS and Windows users (Compose Desktop supports them, but they are not the primary target).

## Value proposition

| | Electron Discord | Puklic |
|---|---|---|
| RAM idle | 400–800 MB | < 150 MB |
| Cold start | 5–10 s | < 2 s |
| Wayland | XWayland fallback with bugs | XWayland → native plan |
| Screenshare on Wayland | broken / partial | xdg-desktop-portal + PipeWire |
| Codebase | Electron + React | Kotlin + Compose Multiplatform |
| iOS/Android | official iOS, official Android | shared codebase |

## MVP scope (Phase 1)

A usable read+write client for everyday communication:
- Login (token paste)
- Guild + channel browser
- Text chat: read, send, edit, delete
- Basic markdown + Unicode emoji
- Lazy message loading + SQLite cache
- Settings (account, appearance, cache limits)
- Notifications (desktop)

**Out of MVP:** voice, video, screenshare, custom emoji, mention resolution, link previews, reactions UI, threads, stickers, Nitro features. These arrive in later phases.

## Out of scope (permanently)

- User account automation
- Bot framework
- AI integration (auto-translate, auto-summarize)
- Modification or plugin for the official Discord client
- Self-bot features (bulk actions, scheduled messages)

## Success metrics

Phase 1 ship:
- 1 week of daily use by the author without crash / OOM
- RAM idle < 150 MB verified
- Cold start < 2 s verified
- Memory leak test: 24 h run, RAM growth < 50 MB

Phase 2 ship:
- Fully usable for text communication with rich content (attachments, reactions, mentions)

Phase 3+ are nice-to-have, not a success blocker for "daily driver" status.

## Anti-goals

- Feature parity with the official client **is not the goal**. Some Discord features (Activity, Stage, Boosts UI, Shop) will not be implemented at all.
- A customization framework / plugins are not planned.
- A server-side mirror / cache proxy / bridge to another protocol is not planned.
