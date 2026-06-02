package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice
import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * iOS `actual` for [listAudioDevices].
 *
 * iOS abstracts the physical microphone / speaker hierarchy behind `AVAudioSession`. The
 * concrete route (Built-in / AirPods / Bluetooth / Car Play) is chosen by the system at
 * session-activation time based on user-level routing preferences in Settings → Sounds —
 * not by the app. The Discord product surface presents a flat device picker that, on
 * iOS, would conflict with the system's own routing UX.
 *
 * Per architect plan FP-14h-5.5 (issue #62) we therefore expose a single virtual entry
 * per direction marked `isDefault = true`. Picking the actual hardware (e.g. switching
 * to AirPods mid-call) is handled by the iOS Control Center route picker which iOS
 * surfaces to the user in-place over our UI — no app code required.
 *
 * Returning a stable single entry keeps the cross-platform `VoiceClient.devices`
 * contract honest (never empty, exactly one default per direction) and matches the
 * way `JavaSoundDevices` enumerates the default mixer on JVM platforms in practice.
 */
@PuklicVoiceCodec
public actual fun listAudioDevices(direction: AudioDevice.Direction): List<AudioDevice> {
    val (id, label) = when (direction) {
        AudioDevice.Direction.Capture -> IOS_DEFAULT_INPUT_ID to IOS_DEFAULT_INPUT_NAME
        AudioDevice.Direction.Playback -> IOS_DEFAULT_OUTPUT_ID to IOS_DEFAULT_OUTPUT_NAME
    }
    return listOf(
        AudioDevice(
            id = id,
            displayName = label,
            direction = direction,
            isDefault = true,
        ),
    )
}

private const val IOS_DEFAULT_INPUT_ID: String = "ios-default-input"
private const val IOS_DEFAULT_INPUT_NAME: String = "System Microphone"
private const val IOS_DEFAULT_OUTPUT_ID: String = "ios-default-output"
private const val IOS_DEFAULT_OUTPUT_NAME: String = "System Output"
