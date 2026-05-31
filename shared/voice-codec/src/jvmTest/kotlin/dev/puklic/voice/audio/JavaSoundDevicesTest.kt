package dev.puklic.voice.audio

import dev.puklic.voice.AudioDevice
import io.kotest.matchers.shouldNotBe
import javax.sound.sampled.AudioSystem
import kotlin.test.Test

class JavaSoundDevicesTest {

    @Test
    fun `listAudioDevices returns capture devices when host has any`() {
        // Sandbox environments may have zero audio mixers — skip in that case rather than fail.
        if (AudioSystem.getMixerInfo().isEmpty()) return
        val devices = listAudioDevices(AudioDevice.Direction.Capture)
        // We don't require non-empty (some CI hosts only expose playback mixers), but the
        // call must succeed and devices, if returned, must have the right direction.
        devices.forEach { it.direction shouldNotBe AudioDevice.Direction.Playback }
    }

    @Test
    fun `listAudioDevices returns playback devices when host has any`() {
        if (AudioSystem.getMixerInfo().isEmpty()) return
        val devices = listAudioDevices(AudioDevice.Direction.Playback)
        devices.forEach { it.direction shouldNotBe AudioDevice.Direction.Capture }
    }
}
