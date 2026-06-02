package dev.puklic.voice.audio

import dev.puklic.voice.codec.PuklicVoiceCodec

import dev.puklic.voice.AudioDevice
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * JVM `actual` for [listAudioDevices].
 *
 * Per architect report 2026-05-23-voice.md §10. Filters `AudioSystem.getMixerInfo()` to
 * mixers exposing the right line class for the requested direction.
 *
 * `isDefault` is set on the first mixer reported by `AudioSystem` that supports the
 * requested line — this matches the OS default mixer convention on macOS/Linux.
 */
@PuklicVoiceCodec
public actual fun listAudioDevices(direction: AudioDevice.Direction): List<AudioDevice> {
    val lineClass = when (direction) {
        AudioDevice.Direction.Capture -> TargetDataLine::class.java
        AudioDevice.Direction.Playback -> SourceDataLine::class.java
    }
    val matches = AudioSystem.getMixerInfo().mapNotNull { info ->
        val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: return@mapNotNull null
        val lineInfos = when (direction) {
            AudioDevice.Direction.Capture -> mixer.targetLineInfo
            AudioDevice.Direction.Playback -> mixer.sourceLineInfo
        }
        val supports = lineInfos.any { lineClass.isAssignableFrom(it.lineClass) }
        if (!supports) null else info
    }
    return matches.mapIndexed { idx, info ->
        AudioDevice(
            id = info.name,
            displayName = if (info.description.isNullOrBlank()) info.name
            else "${info.name} - ${info.description}",
            direction = direction,
            isDefault = idx == 0,
        )
    }
}
