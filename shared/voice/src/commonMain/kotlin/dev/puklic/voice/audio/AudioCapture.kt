package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice

/**
 * Microphone capture abstraction.
 *
 * One frame = 20 ms @ 48 kHz mono S16LE = 960 samples (per `AudioConstants`).
 * `read()` blocks until one full frame is available (natural pacing).
 *
 * Per architect report `2026-05-23-voice.md` §8 (Capture pipeline) + §10 (Device enumeration).
 */
internal interface AudioCapture : AutoCloseable {
    /** Open the device. `deviceId == null` means system default. Idempotent on the same id. */
    fun start(deviceId: String?)

    /** Stop the device but allow re-`start()`. */
    fun stop()

    /** Blocking read of one 20-ms frame. Throws if not started. */
    fun read(): ShortArray

    override fun close()
}

internal expect fun audioCapture(): AudioCapture

/**
 * Enumerate audio devices for the given direction (Capture or Playback).
 * Per §10 — mixer info filtered by line class.
 */
internal expect fun listAudioDevices(direction: AudioDevice.Direction): List<AudioDevice>
