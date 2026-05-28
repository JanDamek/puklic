package dev.puklic.voice.screenshare.linux

import org.freedesktop.dbus.types.Variant

/**
 * One PipeWire sub-stream returned by `org.freedesktop.portal.ScreenCast.Start`.
 *
 * The portal's `Response` signal carries `streams: a(ua{sv})` — an array of
 * `(node_id, properties)` tuples. Each tuple is one independently-attachable PipeWire node;
 * the libavdevice `pipewire` demuxer is opened once per node id.
 *
 * Kinds:
 *  - [PortalStreamKind.Video] — has a `size: (ii)` property describing pixel dimensions.
 *    These feed [dev.puklic.voice.screenshare.encoder.LibavVideoEncoder].
 *  - [PortalStreamKind.Audio] — has no `size`; emitted by the compositor when the
 *    `SelectSources(audio=true)` option is honoured. Currently parsed but not yet
 *    consumed by an encoder (audio capture wiring is tracked separately in issue #25).
 *
 * # Compositor support matrix (verified May 2026)
 *
 * | Compositor          | Audio sub-stream emitted? |
 * |---------------------|---------------------------|
 * | GNOME Mutter ≥ 45   | Yes — separate node id     |
 * | KDE KWin ≥ 6.0      | Partial — gated by user PipeWire setup; sometimes embedded |
 * | wlroots (Sway, Hyprland) | No — audio request silently ignored |
 *
 * The detection heuristic (presence of `size`) keeps working across all variants: a node
 * without `size` is treated as audio regardless of which compositor emitted it.
 */
internal data class PortalStream(
    val nodeId: Int,
    val kind: PortalStreamKind,
    val properties: Map<String, Variant<*>>,
)

internal enum class PortalStreamKind { Video, Audio }

/** Convenience: just the video PipeWire node ids in original order. */
internal fun List<PortalStream>.videoNodes(): List<Int> =
    filter { it.kind == PortalStreamKind.Video }.map { it.nodeId }

/** Convenience: just the audio PipeWire node ids in original order. */
internal fun List<PortalStream>.audioNodes(): List<Int> =
    filter { it.kind == PortalStreamKind.Audio }.map { it.nodeId }
