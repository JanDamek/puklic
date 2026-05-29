package dev.puklic.platform.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.SecureStorage
import java.nio.charset.StandardCharsets

/**
 * Windows Credential Manager via JNA. The jna-platform `Advapi32` interface
 * does not expose the `Cred*` family, so we declare our own thin
 * [CredentialManagerLibrary] surface mapping the four Win32 entry points we
 * need.
 *
 * Credential blobs are stored as raw UTF-16LE bytes (NOT a JNA `WString`) so
 * tokens containing a U+0000 code point are not silently truncated. The byte
 * length is tracked explicitly per the MSDN contract.
 *
 * Target names are namespaced as `puklic-client:<key>` so [list] can
 * enumerate this app's credentials without touching unrelated entries.
 */
class WindowsSecureStorage(
    private val serviceName: String = DEFAULT_SERVICE,
    private val lib: CredentialManagerLibrary = CredentialManagerLibrary.INSTANCE,
) : SecureStorage {

    override suspend fun put(key: String, value: String) {
        val target = targetFor(key)
        val blobBytes = value.toByteArray(StandardCharsets.UTF_16LE)
        val blobMem = Memory(blobBytes.size.toLong().coerceAtLeast(1L))
        if (blobBytes.isNotEmpty()) {
            blobMem.write(0, blobBytes, 0, blobBytes.size)
        }
        val cred = CredentialManagerLibrary.CREDENTIAL().apply {
            Flags = 0
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target)
            Comment = null
            CredentialBlobSize = blobBytes.size
            CredentialBlob = blobMem
            Persist = CRED_PERSIST_LOCAL_MACHINE
            AttributeCount = 0
            Attributes = null
            TargetAlias = null
            UserName = WString(serviceName)
        }
        if (!lib.CredWriteW(cred, 0)) {
            throw PlatformFailed(
                "CredWriteW failed (GetLastError=${Native.getLastError()}) for target=$target",
            )
        }
    }

    override suspend fun get(key: String): String? {
        val target = targetFor(key)
        val ref = PointerByReference()
        if (!lib.CredReadW(WString(target), CRED_TYPE_GENERIC, 0, ref)) {
            val err = Native.getLastError()
            if (err == ERROR_NOT_FOUND) return null
            throw PlatformFailed("CredReadW failed (GetLastError=$err) for target=$target")
        }
        val ptr = ref.value ?: return null
        try {
            val cred = CredentialManagerLibrary.CREDENTIAL(ptr)
            cred.read()
            val size = cred.CredentialBlobSize
            if (size <= 0 || cred.CredentialBlob == null) return ""
            val bytes = cred.CredentialBlob!!.getByteArray(0, size)
            return String(bytes, StandardCharsets.UTF_16LE)
        } finally {
            lib.CredFree(ptr)
        }
    }

    override suspend fun remove(key: String) {
        val target = targetFor(key)
        if (!lib.CredDeleteW(WString(target), CRED_TYPE_GENERIC, 0)) {
            val err = Native.getLastError()
            if (err != ERROR_NOT_FOUND) {
                throw PlatformFailed("CredDeleteW failed (GetLastError=$err) for target=$target")
            }
        }
    }

    override suspend fun list(): List<String> {
        val filter = "$serviceName$TARGET_SEPARATOR*"
        val countRef = IntByReference()
        val arrRef = PointerByReference()
        if (!lib.CredEnumerateW(WString(filter), 0, countRef, arrRef)) {
            val err = Native.getLastError()
            if (err == ERROR_NOT_FOUND) return emptyList()
            throw PlatformFailed("CredEnumerateW failed (GetLastError=$err) for filter=$filter")
        }
        val base = arrRef.value ?: return emptyList()
        try {
            val count = countRef.value
            val results = ArrayList<String>(count)
            for (i in 0 until count) {
                val entryPtr = base.getPointer((i * Native.POINTER_SIZE).toLong()) ?: continue
                val cred = CredentialManagerLibrary.CREDENTIAL(entryPtr)
                cred.read()
                val targetName = cred.TargetName?.toString() ?: continue
                val prefix = "$serviceName$TARGET_SEPARATOR"
                if (targetName.startsWith(prefix)) {
                    results += targetName.substring(prefix.length)
                }
            }
            return results.distinct()
        } finally {
            lib.CredFree(base)
        }
    }

    private fun targetFor(key: String): String = "$serviceName$TARGET_SEPARATOR$key"

    companion object {
        internal const val DEFAULT_SERVICE = "puklic-client"
        internal const val TARGET_SEPARATOR = ":"

        // Win32 cred type — see WinCred.h CRED_TYPE_GENERIC.
        internal const val CRED_TYPE_GENERIC: Int = 1

        // Persist scope — see WinCred.h CRED_PERSIST_LOCAL_MACHINE.
        internal const val CRED_PERSIST_LOCAL_MACHINE: Int = 2

        // System error code ERROR_NOT_FOUND from WinError.h.
        internal const val ERROR_NOT_FOUND: Int = 1168
    }

    /**
     * JNA mapping of the four `Advapi32.dll` credential management entry
     * points used here. Declared as a UTF-16 (`W`) family — credential names
     * may contain non-ASCII characters and Windows itself stores them as
     * wide strings.
     */
    interface CredentialManagerLibrary : StdCallLibrary {
        fun CredReadW(
            targetName: WString,
            type: Int,
            flags: Int,
            credential: PointerByReference,
        ): Boolean

        fun CredWriteW(credential: CREDENTIAL, flags: Int): Boolean

        fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean

        fun CredEnumerateW(
            filter: WString,
            flags: Int,
            count: IntByReference,
            credentials: PointerByReference,
        ): Boolean

        fun CredFree(buffer: Pointer)

        /**
         * CREDENTIAL struct from WinCred.h. Field order and types match
         * exactly. The structure is auto-mapped by JNA via reflection on
         * the public field declarations below.
         */
        @Suppress("PropertyName", "VariableNaming")
        @Structure.FieldOrder(
            "Flags",
            "Type",
            "TargetName",
            "Comment",
            "LastWritten",
            "CredentialBlobSize",
            "CredentialBlob",
            "Persist",
            "AttributeCount",
            "Attributes",
            "TargetAlias",
            "UserName",
        )
        class CREDENTIAL : Structure {
            @JvmField var Flags: Int = 0

            @JvmField var Type: Int = 0

            @JvmField var TargetName: WString? = null

            @JvmField var Comment: WString? = null

            @JvmField var LastWritten: FILETIME = FILETIME()

            @JvmField var CredentialBlobSize: Int = 0

            @JvmField var CredentialBlob: Pointer? = null

            @JvmField var Persist: Int = 0

            @JvmField var AttributeCount: Int = 0

            @JvmField var Attributes: Pointer? = null

            @JvmField var TargetAlias: WString? = null

            @JvmField var UserName: WString? = null

            constructor() : super()
            constructor(p: Pointer) : super(p)
        }

        @Suppress("PropertyName", "VariableNaming")
        @Structure.FieldOrder("dwLowDateTime", "dwHighDateTime")
        class FILETIME : Structure() {
            @JvmField var dwLowDateTime: Int = 0

            @JvmField var dwHighDateTime: Int = 0
        }

        companion object {
            val INSTANCE: CredentialManagerLibrary by lazy {
                Native.load(
                    "Advapi32",
                    CredentialManagerLibrary::class.java,
                    W32APIOptions.UNICODE_OPTIONS,
                )
            }
        }
    }
}
