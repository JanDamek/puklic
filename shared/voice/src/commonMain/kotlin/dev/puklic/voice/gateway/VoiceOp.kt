package dev.puklic.voice.gateway

/**
 * Discord voice gateway opcodes used by Puklic Phase 3.0.
 *
 * Source of truth: `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5.
 * Opcodes 14..18 are reserved for DAVE (E2EE) and intentionally not used in 3.0.
 */
internal object VoiceOp {
    const val IDENTIFY: Int = 0
    const val SELECT_PROTOCOL: Int = 1
    const val READY: Int = 2
    const val HEARTBEAT: Int = 3
    const val SESSION_DESCRIPTION: Int = 4
    const val SPEAKING: Int = 5
    const val HEARTBEAT_ACK: Int = 6
    const val RESUME: Int = 7
    const val HELLO: Int = 8
    const val RESUMED: Int = 9
    const val VIDEO_STREAM: Int = 12
    const val CLIENT_DISCONNECT: Int = 13

    // DAVE — not implemented in Phase 3.0, reserved for 3.1+.
    const val DAVE_PREPARE_TRANSITION: Int = 14
    const val DAVE_EXECUTE_TRANSITION: Int = 15
    const val DAVE_TRANSITION_READY: Int = 16
    const val DAVE_PREPARE_EPOCH: Int = 17
    const val DAVE_MLS_EXTERNAL_SENDER: Int = 18
}
