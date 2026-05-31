@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package dev.puklic.voice.audio

import dev.puklic.voice.codec.PuklicVoiceCodec
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.setActive

/**
 * iOS-only `AVAudioSession` lifecycle controller for the voice-codec audio engines.
 *
 * `AVAudioSession.sharedInstance()` is the iOS-level brokerage between the app and the
 * system audio routing graph: setting category `.playAndRecord` + mode `.voiceChat`
 * tells iOS to route through the earpiece by default, enable the AEC (acoustic echo
 * canceller) tuned for VoIP, allow Bluetooth HFP, and lower other apps' audio.
 *
 * Reference-counted activate/deactivate so capture and playback can both `acquire()`
 * without fighting over the session: only the first acquirer activates, only the last
 * `release()` deactivates. iOS prefers this over toggling activation every frame.
 *
 * Per architect plan §4.1 in
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp14h-3-implementation.md`.
 */
@PuklicVoiceCodec
internal object IosAudioSession {
    private val refCount = AtomicInt(0)

    fun acquire() {
        val newCount = refCount.fetchAndAdd(1) + 1
        if (newCount == 1) {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeVoiceChat,
                options = (
                    AVAudioSessionCategoryOptionDefaultToSpeaker or
                        AVAudioSessionCategoryOptionAllowBluetooth
                    ),
                error = null,
            )
            session.setActive(true, error = null)
        }
    }

    fun release() {
        val newCount = refCount.fetchAndAdd(-1) - 1
        check(newCount >= 0) { "IosAudioSession release() without matching acquire()" }
        if (newCount == 0) {
            AVAudioSession.sharedInstance().setActive(false, error = null)
        }
    }
}
