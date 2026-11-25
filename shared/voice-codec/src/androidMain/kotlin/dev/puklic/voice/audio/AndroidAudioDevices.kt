package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice
import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * Android `actual` for [listAudioDevices].
 *
 * Android routes a voice call through the system communication route (earpiece, speaker, wired or
 * Bluetooth headset), which the app layer selects with `AudioManager` for the whole call. Exposing
 * individual `AudioDeviceInfo` entries here would need a `Context` and would fight that routing, so
 * each direction exposes one default entry, mirroring the iOS actual.
 */
@PuklicVoiceCodec
public actual fun listAudioDevices(direction: AudioDevice.Direction): List<AudioDevice> {
    val (id, label) = when (direction) {
        AudioDevice.Direction.Capture -> ANDROID_DEFAULT_INPUT_ID to ANDROID_DEFAULT_INPUT_NAME
        AudioDevice.Direction.Playback -> ANDROID_DEFAULT_OUTPUT_ID to ANDROID_DEFAULT_OUTPUT_NAME
    }
    return listOf(AudioDevice(id = id, displayName = label, direction = direction, isDefault = true))
}

private const val ANDROID_DEFAULT_INPUT_ID: String = "android-default-input"
private const val ANDROID_DEFAULT_INPUT_NAME: String = "System Microphone"
private const val ANDROID_DEFAULT_OUTPUT_ID: String = "android-default-output"
private const val ANDROID_DEFAULT_OUTPUT_NAME: String = "System Output"
