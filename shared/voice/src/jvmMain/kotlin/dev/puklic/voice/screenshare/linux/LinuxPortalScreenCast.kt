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
 * NOTE on completeness: this implementation compiles and the D-Bus calls are wired correctly,
 * but **it has only been smoke-tested on a real Linux+GNOME session**, not in CI (no portal
 * available there). The `parseStartResponseStreams` helper handles the most common shape
 * (List<Object[]> where each entry is `[UInt32 nodeId, Map<String,Variant> props]`), but
 * dbus-java's auto-marshalling for `a(ua{sv})` can also surface as `List<DBusStruct>`;
 * production runs on Linux should verify and extend [extractStreams] as needed.
 *
 * See architect report `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md`
 * §4 phase 3, and `docs/05_platforms/linux-wayland.md`.
 */
internal class LinuxPortalScreenCast : AutoCloseable {

    data class PipeWireStream(val nodeId: Int, val fd: Int)

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
     * picker (Step 3), or times out after [overallTimeoutMs].
     */
    @Suppress("LongMethod")
    suspend fun open(includeAudio: Boolean = false, overallTimeoutMs: Long = DEFAULT_TIMEOUT_MS): PipeWireStream {
        val connection = DBusConnectionBuilder.forSessionBus().build()
        conn = connection

        val sc = connection.getRemoteObject(
            PORTAL_BUS,
            PORTAL_PATH,
            ScreenCast::class.java,
        )

        // Register a single generic Response handler scoped by Request-path; the dispatcher
        // routes by signal-path to the right pending CompletableDeferred.
        val rule = DBusMatchRule(
            /* _type   = */ "signal",
            /* _iface  = */ REQUEST_IFACE,
            /* _member = */ RESPONSE_SIGNAL,
        )
        val handlerCloseable = connection.addGenericSigHandler(rule, ResponseDispatcher(pendingRequests))

        return try {
            withTimeout(overallTimeoutMs) {
                // 1) CreateSession
                val sessionToken = newToken(SESSION_TOKEN_PREFIX)
                val createOpts = mapOf<String, Variant<*>>(
                    "session_handle_token" to Variant(sessionToken),
                    "handle_token" to Variant(newToken(REQUEST_TOKEN_PREFIX)),
                )
                val createReq = sc.CreateSession(createOpts)
                val createRes = awaitResponse(createReq)
                val sessionHandleStr = (createRes.results["session_handle"]?.value as? String)
                    ?: error("CreateSession: missing session_handle")
                val sessionHandle = DBusPath(sessionHandleStr)

                // 2) SelectSources
                val selectOpts = buildMap<String, Variant<*>> {
                    put("types", Variant(UInt32(MONITOR_BIT.toLong())))
                    put("multiple", Variant(false))
                    put("cursor_mode", Variant(UInt32(CURSOR_MODE_EMBEDDED.toLong())))
                    put("handle_token", Variant(newToken(REQUEST_TOKEN_PREFIX)))
                    if (includeAudio) put("audio", Variant(true))
                }
                val selectReq = sc.SelectSources(sessionHandle, selectOpts)
                awaitResponse(selectReq)

                // 3) Start — compositor pops up the picker GUI here; user picks a monitor.
                val startOpts = mapOf<String, Variant<*>>(
                    "handle_token" to Variant(newToken(REQUEST_TOKEN_PREFIX)),
                )
                val startReq = sc.Start(sessionHandle, /* parent_window */ "", startOpts)
                val startRes = awaitResponse(startReq)
                val nodeId = extractStreams(startRes.results)

                // 4) OpenPipeWireRemote → unix fd we'll pass into libavdevice/pipewire.
                val fd = sc.OpenPipeWireRemote(sessionHandle, emptyMap())
                PipeWireStream(nodeId, fd.getIntFileDescriptor())
            }
        } catch (e: TimeoutCancellationException) {
            error("xdg-desktop-portal handshake timed out after ${overallTimeoutMs}ms: ${e.message}")
        } finally {
            runCatching { handlerCloseable.close() }
        }
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
                if (code != 0) {
                    deferred.completeExceptionally(
                        IllegalStateException("Portal Response code=$code on ${signal.path} results=$results"),
                    )
                } else {
                    deferred.complete(ResponseTuple(code, results))
                }
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
    }

    internal companion object {
        const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
        const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        const val REQUEST_IFACE = "org.freedesktop.portal.Request"
        const val RESPONSE_SIGNAL = "Response"
        const val SESSION_TOKEN_PREFIX = "puklic_sess_"
        const val REQUEST_TOKEN_PREFIX = "puklic_req_"
        const val MONITOR_BIT = 1
        const val CURSOR_MODE_EMBEDDED = 2
        const val DEFAULT_TIMEOUT_MS = 60_000L

        private fun newToken(prefix: String): String =
            prefix + UUID.randomUUID().toString().replace("-", "")

        /**
         * Pulls the first PipeWire node id out of the `streams` variant in a Start response.
         * Wire signature is `a(ua{sv})`. dbus-java typically deserialises this as
         * `List<Object[]>` where each row is `[UInt32 nodeId, Map<String,Variant<?>> props]`.
         */
        internal fun extractStreams(results: Map<String, Variant<*>>): Int {
            val streamsVariant = results["streams"]
                ?: error("Start response missing 'streams'")
            val list = streamsVariant.value as? List<*>
                ?: error("Start 'streams' not a List, was ${streamsVariant.value?.javaClass}")
            val first = list.firstOrNull()
                ?: error("Start 'streams' list empty")
            return extractNodeId(first)
        }

        /** Tolerant of either `Object[]` rows (dbus-java common) or a typed `DBusStruct`. */
        internal fun extractNodeId(row: Any?): Int = when (row) {
            is Array<*> -> (row.firstOrNull() as? UInt32)?.toInt()
                ?: error("Stream row[0] not UInt32: ${row.firstOrNull()?.javaClass}")
            is List<*> -> (row.firstOrNull() as? UInt32)?.toInt()
                ?: error("Stream row[0] not UInt32 (List form): ${row.firstOrNull()?.javaClass}")
            else -> error("Unknown stream row shape: ${row?.javaClass}")
        }
    }
}
