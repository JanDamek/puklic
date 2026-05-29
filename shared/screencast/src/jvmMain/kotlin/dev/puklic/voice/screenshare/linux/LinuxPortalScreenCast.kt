package dev.puklic.voice.screenshare.linux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusMatchRule
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wayland screen capture via xdg-desktop-portal's `org.freedesktop.portal.ScreenCast` interface
 * over the session D-Bus. Returns a PipeWire node id + fd that libavdevice's `pipewire` input
 * can read from.
 *
 * Flow (per portal spec):
 *   1. `CreateSession` → returns a request handle path; portal emits `Response` signal on that
 *      path with `{ session_handle: "/..." }` once user-side prep completes (no UI here yet).
 *   2. `SelectSources(session, {types, multiple, cursor_mode[, audio]})` → request path; Response
 *      arrives once the compositor's source-picker dialog is configured (still no UI).
 *   3. `Start(session, parent_window, {})` → request path; this is when the compositor pops up
 *      the picker GUI. Response carries `{ streams: a(ua{sv}) }` once the user confirms.
 *   4. `OpenPipeWireRemote(session, {})` → returns a unix fd to a PipeWire remote endpoint that
 *      libavdevice's `pipewire` demuxer can attach to with `node_id=<id>` option.
 *
 * Lifetime: this class owns a [DBusConnection] for the duration of a single capture session.
 * Caller (DefaultScreenShareClient on Linux) is responsible for invoking [close] when capture
 * tears down so the session is released compositor-side.
 *
 * See architect report `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md`
 * §4 phase 3, and `docs/05_platforms/linux-wayland.md`.
 */
public class LinuxPortalScreenCast : AutoCloseable {

    /** What sources the portal picker offers the user. Bit-mask per portal spec. */
    enum class CaptureMode(val mask: Int) {
        Monitors(MASK_MONITORS),
        Windows(MASK_WINDOWS),
        MonitorsAndWindows(MASK_MONITORS or MASK_WINDOWS),
    }

    /**
     * How the cursor is rendered in the captured stream (per portal spec).
     * - [Hidden] — never include the cursor.
     * - [Embedded] — composite the cursor into the framebuffer (default).
     * - [Metadata] — deliver cursor position as PipeWire metadata; client renders it.
     */
    enum class CursorMode(val mask: Int) {
        Hidden(CURSOR_MASK_HIDDEN),
        Embedded(CURSOR_MASK_EMBEDDED),
        Metadata(CURSOR_MASK_METADATA),
    }

    /**
     * Result of a successful portal handshake: the PipeWire endpoint fd plus every sub-stream
     * (video and audio) the compositor granted. Audio sub-streams only appear when both
     * (a) `SelectSources(audio=true)` was requested and (b) the compositor honours it
     * (see `PortalStream` kdoc for the support matrix).
     */
    data class PipeWireStream(val streams: List<PortalStream>, val fd: Int) {
        /** First video node id — most picks are a single monitor or single window. */
        val firstVideoNodeId: Int
            get() = streams.videoNodes().firstOrNull()
                ?: error("PipeWireStream has no video sub-stream (only audio?)")

        /** First audio node id, or null if the compositor did not emit one. */
        val firstAudioNodeId: Int? get() = streams.audioNodes().firstOrNull()
    }

    /**
     * Result of [open]. Distinguishes the three terminal portal states:
     *  - [Ok] — user accepted, PipeWire fd ready.
     *  - [UserCancelled] — user dismissed the picker (Response code 1).
     *  - [Error] — portal returned code 2, threw, or another failure (e.g. dbus connect failed).
     */
    sealed interface PortalResult {
        data class Ok(val stream: PipeWireStream) : PortalResult
        data object UserCancelled : PortalResult
        data class Error(val message: String) : PortalResult
    }

    /** Manually-defined stub for the portal's ScreenCast interface (dbus-java works fine with this). */
    @Suppress("FunctionNaming")
    interface ScreenCast : DBusInterface {
        fun CreateSession(options: Map<String, Variant<*>>): DBusPath
        fun SelectSources(session: DBusPath, options: Map<String, Variant<*>>): DBusPath
        fun Start(session: DBusPath, parent_window: String, options: Map<String, Variant<*>>): DBusPath
        fun OpenPipeWireRemote(session: DBusPath, options: Map<String, Variant<*>>): FileDescriptor
    }

    private var conn: DBusConnection? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<ResponseTuple>>()

    /**
     * Run the full portal handshake. Suspends until the user picks a source in the compositor
     * picker (Step 3), cancels, or times out after [overallTimeoutMs]. Never throws for
     * user-cancellation — returns [PortalResult.UserCancelled] instead, so the UI does not
     * surface an error when the user simply closes the picker.
     */
    suspend fun open(
        captureMode: CaptureMode = CaptureMode.Monitors,
        cursorMode: CursorMode = CursorMode.Hidden,
        includeAudio: Boolean = false,
        parentWindow: String = "",
        overallTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PortalResult {
        return try {
            PortalResult.Ok(runHandshake(captureMode, cursorMode, includeAudio, parentWindow, overallTimeoutMs))
        } catch (e: UserCancelledException) {
            PortalResult.UserCancelled
        } catch (e: TimeoutCancellationException) {
            PortalResult.Error("xdg-desktop-portal handshake timed out after ${overallTimeoutMs}ms")
        } catch (e: Throwable) {
            PortalResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("LongMethod")
    private suspend fun runHandshake(
        captureMode: CaptureMode,
        cursorMode: CursorMode,
        includeAudio: Boolean,
        parentWindow: String,
        overallTimeoutMs: Long,
    ): PipeWireStream {
        val connection = DBusConnectionBuilder.forSessionBus().build()
        conn = connection

        val sc = connection.getRemoteObject(PORTAL_BUS, PORTAL_PATH, ScreenCast::class.java)

        val rule = DBusMatchRule(
            /* _type   = */ "signal",
            /* _iface  = */ REQUEST_IFACE,
            /* _member = */ RESPONSE_SIGNAL,
        )
        val handlerCloseable = connection.addGenericSigHandler(rule, ResponseDispatcher(pendingRequests))

        return try {
            withTimeout(overallTimeoutMs) {
                val sessionHandle = createSession(sc)
                selectSources(sc, sessionHandle, captureMode, cursorMode, includeAudio)
                val streams = start(sc, sessionHandle, parentWindow)
                val fd = sc.OpenPipeWireRemote(sessionHandle, emptyMap())
                PipeWireStream(streams, fd.getIntFileDescriptor())
            }
        } finally {
            runCatching { handlerCloseable.close() }
        }
    }

    private suspend fun createSession(sc: ScreenCast): DBusPath {
        val opts = mapOf<String, Variant<*>>(
            "session_handle_token" to Variant(newToken(SESSION_TOKEN_PREFIX)),
            "handle_token" to Variant(newToken(REQUEST_TOKEN_PREFIX)),
        )
        val req = sc.CreateSession(opts)
        val res = awaitResponse(req)
        val handle = res.results["session_handle"]?.value as? String
            ?: error("CreateSession: missing session_handle")
        return DBusPath(handle)
    }

    private suspend fun selectSources(
        sc: ScreenCast,
        session: DBusPath,
        captureMode: CaptureMode,
        cursorMode: CursorMode,
        includeAudio: Boolean,
    ) {
        val opts = buildMap<String, Variant<*>> {
            put("types", Variant(UInt32(captureMode.mask.toLong())))
            put("multiple", Variant(false))
            put("cursor_mode", Variant(UInt32(cursorMode.mask.toLong())))
            put("handle_token", Variant(newToken(REQUEST_TOKEN_PREFIX)))
            if (includeAudio) put("audio", Variant(true))
        }
        awaitResponse(sc.SelectSources(session, opts))
    }

    private suspend fun start(sc: ScreenCast, session: DBusPath, parentWindow: String): List<PortalStream> {
        val opts = mapOf<String, Variant<*>>(
            "handle_token" to Variant(newToken(REQUEST_TOKEN_PREFIX)),
        )
        val res = awaitResponse(sc.Start(session, parentWindow, opts))
        return extractAllStreams(res.results)
    }

    private suspend fun awaitResponse(requestPath: DBusPath): ResponseTuple {
        val key = requestPath.path
        val deferred = CompletableDeferred<ResponseTuple>()
        pendingRequests[key] = deferred
        return try {
            deferred.await()
        } finally {
            pendingRequests.remove(key)
        }
    }

    override fun close() {
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        runCatching { conn?.disconnect() }
        conn = null
    }

    private data class ResponseTuple(val responseCode: Int, val results: Map<String, Variant<*>>)

    private class UserCancelledException : RuntimeException("User dismissed portal picker")

    /**
     * Generic dispatcher: every `org.freedesktop.portal.Request.Response` signal arrives here;
     * we look up the matching pending request by path and complete its deferred. Unmatched
     * signals are ignored (could be for a sibling capture session).
     */
    private class ResponseDispatcher(
        private val pending: ConcurrentHashMap<String, CompletableDeferred<ResponseTuple>>,
    ) : DBusSigHandler<DBusSignal> {
        @Suppress("UNCHECKED_CAST")
        override fun handle(signal: DBusSignal) {
            val deferred = pending[signal.path] ?: return
            try {
                val params = signal.parameters
                val code = ((params.getOrNull(0) as? UInt32)?.toInt()) ?: -1
                val results = (params.getOrNull(1) as? Map<String, Variant<*>>) ?: emptyMap()
                when (decodeResponseCode(code)) {
                    ResponseCode.Ok -> deferred.complete(ResponseTuple(code, results))
                    ResponseCode.UserCancelled -> deferred.completeExceptionally(UserCancelledException())
                    ResponseCode.Error -> deferred.completeExceptionally(
                        IllegalStateException("Portal Response error code=$code on ${signal.path} results=$results"),
                    )
                }
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
    }

    /** Per portal Request.Response spec: 0=ok, 1=user-cancelled, 2=other error. */
    public enum class ResponseCode { Ok, UserCancelled, Error }

    public companion object {
        const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
        const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        const val REQUEST_IFACE = "org.freedesktop.portal.Request"
        const val RESPONSE_SIGNAL = "Response"
        const val SESSION_TOKEN_PREFIX = "puklic_sess_"
        const val REQUEST_TOKEN_PREFIX = "puklic_req_"

        // Portal SourceType bitmask (org.freedesktop.portal.ScreenCast):
        // 1 = MONITOR, 2 = WINDOW, 4 = VIRTUAL (rarely supported, not exposed).
        const val MASK_MONITORS = 1
        const val MASK_WINDOWS = 2

        // CursorMode bitmask:
        const val CURSOR_MASK_HIDDEN = 1
        const val CURSOR_MASK_EMBEDDED = 2
        const val CURSOR_MASK_METADATA = 4

        const val RESPONSE_CODE_OK = 0
        const val RESPONSE_CODE_CANCELLED = 1
        const val RESPONSE_CODE_ERROR = 2

        const val DEFAULT_TIMEOUT_MS = 60_000L

        public fun decodeResponseCode(code: Int): ResponseCode = when (code) {
            RESPONSE_CODE_OK -> ResponseCode.Ok
            RESPONSE_CODE_CANCELLED -> ResponseCode.UserCancelled
            else -> ResponseCode.Error
        }

        private fun newToken(prefix: String): String =
            prefix + UUID.randomUUID().toString().replace("-", "")

        // Per portal spec `org.freedesktop.portal.ScreenCast`: each stream's properties dict may
        // contain `size: (ii)` (video pixel dims), `source_type: u`, `id: s`, etc. We rely on
        // `size` as the canonical video discriminator — audio sub-streams have no pixel dims.
        const val PORTAL_PROPERTY_SIZE = "size"
        const val PORTAL_PROPERTY_SOURCE_TYPE = "source_type"

        /**
         * Pulls every PipeWire sub-stream out of the `streams` variant in a Start response.
         * Wire signature is `a(ua{sv})`. dbus-java deserialises rows either as `Object[]` or
         * `List<*>` (depending on version); both shapes are accepted. Each row becomes one
         * [PortalStream]; the [PortalStreamKind] is inferred from the presence of a `size`
         * property (see [PORTAL_PROPERTY_SIZE]).
         *
         * Returns an empty list when the compositor returned no streams. Throws on:
         *  - missing `streams` key
         *  - non-list value
         *  - a row that is neither `Array<*>` nor `List<*>` (would mean a dbus-java API break)
         *  - a row whose first element is not a `UInt32` node id
         */
        @Suppress("UNCHECKED_CAST")
        public fun extractAllStreams(results: Map<String, Variant<*>>): List<PortalStream> {
            val streamsVariant = results["streams"]
                ?: error("Start response missing 'streams'")
            val list = streamsVariant.value as? List<*>
                ?: error("Start 'streams' not a List, was ${streamsVariant.value?.javaClass}")
            return list.map { row ->
                val (nodeId, props) = decodeStreamRow(row)
                val kind = if (props.containsKey(PORTAL_PROPERTY_SIZE)) {
                    PortalStreamKind.Video
                } else {
                    PortalStreamKind.Audio
                }
                PortalStream(nodeId = nodeId, kind = kind, properties = props)
            }
        }

        /** Tolerant of either `Object[]` rows (dbus-java common) or a typed list. */
        @Suppress("UNCHECKED_CAST")
        private fun decodeStreamRow(row: Any?): Pair<Int, Map<String, Variant<*>>> {
            val (idCell, propsCell) = when (row) {
                is Array<*> -> row.getOrNull(0) to row.getOrNull(1)
                is List<*> -> row.getOrNull(0) to row.getOrNull(1)
                else -> error("Unknown stream row shape: ${row?.javaClass}")
            }
            val nodeId = (idCell as? UInt32)?.toInt()
                ?: error("Stream row[0] not UInt32: ${idCell?.javaClass}")
            val props = (propsCell as? Map<String, Variant<*>>) ?: emptyMap()
            return nodeId to props
        }
    }
}
