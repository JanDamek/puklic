package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice
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

/**
 * Open the platform default microphone capture pipeline.
 *
 * Promoted to `expect fun` in FP-14h-5.5 so `DefaultVoiceClient` can live in commonMain
 * and pick up the platform actual without per-platform wiring:
 *  - JVM (Linux / Windows / macOS .dmg): JavaSound `TargetDataLine`
 *  - iOS / iPadOS: `AVAudioEngine.inputNode` via `IosAVAudioEngineCapture`
 *
 * Mac App Store JVM builds inject `JnaAVAudioEngineCapture` directly into the DI graph
 * instead of calling this function (the JNA bridge lives in `:desktop:platform-macos-appstore`
 * which is not a Kotlin/Native target of this module).
 */
@PuklicVoiceCodec
public expect fun audioCapture(): AudioCapture

/**
 * Enumerate physical audio devices for the requested direction.
 *
 * Promoted to `expect fun` in FP-14h-5.5 together with [audioCapture]. The result is
 * surfaced to the UI via `VoiceClient.devices`.
 */
@PuklicVoiceCodec
public expect fun listAudioDevices(direction: AudioDevice.Direction): List<AudioDevice>
