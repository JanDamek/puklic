package dev.puklic.voice.dave.gateway

/**
 * Voice-gateway DAVE opcodes per the architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-dave-e2ee.md` §5.
 *
 * Direction column:
 *   S→C = server to client    C→S = client to server
 *
 * Frame shape column:
 *   JSON   = standard `{op, d}` envelope on the WS text channel
 *   binary = `(seq:u16 BE)(op:u8) || MLS bytes` on the WS binary channel
 *            (see [DaveBinaryFrame]).
 */
internal object DaveOp {
    /** S→C JSON `{ protocol_version, transition_id }`. */
    const val PROTOCOL_PREPARE_TRANSITION: Int = 21

    /** S→C JSON `{ transition_id }`. */
    const val PROTOCOL_EXECUTE_TRANSITION: Int = 22

    /** C→S JSON `{ transition_id }`. */
    const val PROTOCOL_READY_FOR_TRANSITION: Int = 23

    /** S→C JSON `{ protocol_version, epoch }`. */
    const val PROTOCOL_PREPARE_EPOCH: Int = 24

    /** S→C binary, MLS ExternalSender bytes. */
    const val MLS_EXTERNAL_SENDER_PACKAGE: Int = 25

    /** C→S binary, MLS KeyPackage bytes. */
    const val MLS_KEY_PACKAGE: Int = 26

    /** S→C binary, MLS Proposals frame. */
    const val MLS_PROPOSALS: Int = 27

    /** C→S binary, Commit + optional Welcome. */
    const val MLS_COMMIT_WELCOME: Int = 28

    /** S→C binary, Commit + transition_id header. */
    const val MLS_ANNOUNCE_COMMIT_TRANSITION: Int = 29

    /** S→C binary, Welcome message. */
    const val MLS_WELCOME: Int = 30

    /** C→S JSON `{ transition_id }`. */
    const val MLS_INVALID_COMMIT_WELCOME: Int = 31
}
