package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice

/**
 * Speaker playback abstraction.
 *
 * One frame = 20 ms @ 48 kHz mono S16LE = 960 samples (per `AudioConstants`).
 * `write()` blocks if the platform buffer is full — that natural back-pressure paces
 * the mixer loop at ~50 Hz once the line is primed.
 *
 * Per architect report `2026-05-23-voice.md` §9 (Playback pipeline) + §10 (Device enumeration).
 */
internal interface AudioPlayback : AutoCloseable {
    /** Open the device. `deviceId == null` means system default. Idempotent on the same id. */
    fun start(deviceId: String?)

    /** Stop the device but allow re-`start()`. */
    fun stop()

    /** Blocking write of one mixed 20-ms frame. Throws if not started. */
    fun write(pcm: ShortArray)

    override fun close()
}

internal expect fun audioPlayback(): AudioPlayback

/**
 * Enumerate audio playback devices. Capture devices are listed via
 * `dev.puklic.voice.audio.listAudioDevices(AudioDevice.Direction.Capture)`.
 *
 * Mirrors the capture-side helper but kept separate per slice-8 design so the
 * common surface stays minimal until both directions ship.
 */
internal expect fun listPlaybackDevices(): List<AudioDevice>
