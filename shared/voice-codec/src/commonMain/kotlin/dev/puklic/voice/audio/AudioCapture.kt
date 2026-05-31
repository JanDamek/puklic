package dev.puklic.voice.audio

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * Microphone capture abstraction.
 *
 * One frame = 20 ms @ 48 kHz mono S16LE = 960 samples (per `AudioConstants`).
 * `read()` blocks until one full frame is available (natural pacing).
 *
 * Per architect report `2026-05-23-voice.md` §8 (Capture pipeline) + §10 (Device enumeration).
 */
@PuklicVoiceCodec
public interface AudioCapture : AutoCloseable {
    /** Open the device. `deviceId == null` means system default. Idempotent on the same id. */
    fun start(deviceId: String?)

    /** Stop the device but allow re-`start()`. */
    fun stop()

    /** Blocking read of one 20-ms frame. Throws if not started. */
    fun read(): ShortArray

    override fun close()
}

// FP-14h-2e (issue #69): `audioCapture()` and `listAudioDevices()` top-level functions
// live in JavaSoundCapture.kt / JavaSoundDevices.kt as plain JVM functions (not
// expect/actual) until FP-14h-3 ships Apple audio actuals and these get promoted to
// commonMain `expect`. Per architect plan §1.1 deferred items.
