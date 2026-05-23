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

    // DAVE — verified opcodes 21-31 (see architect report 2026-05-23-dave-e2ee.md §5).
    // The original 14-18 placeholders were inaccurate guesses; the public-facing
    // constants live in :shared:voice-dave's DaveOp object. This range below is the
    // routing window recognised by DefaultVoiceGatewayConnection for JSON DAVE ops.
    const val DAVE_JSON_MIN: Int = 21
    const val DAVE_JSON_MAX: Int = 24
    const val DAVE_MLS_INVALID_COMMIT_WELCOME: Int = 31
}
