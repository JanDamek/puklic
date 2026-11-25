package dev.puklic.voice.client

import dev.puklic.voice.audio.AudioPlayback
import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlin.concurrent.Volatile

/**
 * [AudioPlayback] decorator that discards mixed frames while deafened instead of stopping the
 * device, so deafening and un-deafening is instant and repeatable. The blocking pacing of
 * [write] is preserved by delegating a silent frame.
 */
@PuklicVoiceCodec
internal class DeafenableAudioPlayback(private val delegate: AudioPlayback) : AudioPlayback {

    @Volatile
    var deafened: Boolean = false

    override fun start(deviceId: String?) = delegate.start(deviceId)

    override fun stop() = delegate.stop()

    override fun write(pcm: ShortArray) = delegate.write(if (deafened) ShortArray(pcm.size) else pcm)

    override fun close() = delegate.close()
}
