package dev.puklic.voice.audio

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * Speaker playback abstraction.
 *
 * One frame = 20 ms @ 48 kHz mono S16LE = 960 samples (per `AudioConstants`).
 * `write()` blocks if the platform buffer is full — that natural back-pressure paces
 * the mixer loop at ~50 Hz once the line is primed.
 *
 * Per architect report `2026-05-23-voice.md` §9 (Playback pipeline) + §10 (Device enumeration).
 */
@PuklicVoiceCodec
public interface AudioPlayback : AutoCloseable {
    /** Open the device. `deviceId == null` means system default. Idempotent on the same id. */
    fun start(deviceId: String?)

    /** Stop the device but allow re-`start()`. */
    fun stop()

    /** Blocking write of one mixed 20-ms frame. Throws if not started. */
    fun write(pcm: ShortArray)

    override fun close()
}

// FP-14h-2e (issue #69): `audioPlayback()` and `listPlaybackDevices()` top-level
// functions live in JavaSoundPlayback.kt as plain JVM functions (not expect/actual)
// until FP-14h-3 ships Apple audio actuals. Per architect plan §1.1 deferred items.
