package dev.puklic.platform.macos.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * JNA bindings for `Security.framework` `SecItem*` APIs and the keychain
 * attribute key globals (`kSecClass`, `kSecAttrService`, `kSecAttrAccount`,
 * `kSecAttrSynchronizable`, `kSecAttrSynchronizableAny`, `kSecValueData`,
 * `kSecMatchLimit`, `kSecMatchLimitAll`, `kSecMatchLimitOne`,
 * `kSecReturnData`, `kSecReturnAttributes`, `kSecClassGenericPassword`).
 *
 * Used by [dev.puklic.platform.macos.MacOsSecureStorage] to store the
 * Discord token in the macOS Keychain with iCloud Keychain sync enabled
 * (Issue #74). The previous `/usr/bin/security` CLI implementation cannot
 * set `kSecAttrSynchronizable` and is therefore unsuitable for cross-device
 * sync.
 */
internal interface Security : Library {

    fun SecItemAdd(attributes: Pointer?, result: PointerByReference?): Int
    fun SecItemCopyMatching(query: Pointer?, result: PointerByReference?): Int
    fun SecItemDelete(query: Pointer?): Int
    fun SecItemUpdate(query: Pointer?, attributesToUpdate: Pointer?): Int

    companion object {
        /** From `SecBase.h`. */
        const val ERR_SEC_SUCCESS: Int = 0
        const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300
        const val ERR_SEC_DUPLICATE_ITEM: Int = -25299

        val INSTANCE: Security by lazy {
            Native.load("Security", Security::class.java)
        }

        private val lib: NativeLibrary by lazy {
            NativeLibrary.getInstance("Security")
        }

        val K_SEC_CLASS: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecClass").getPointer(0)
        }
        val K_SEC_CLASS_GENERIC_PASSWORD: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecClassGenericPassword").getPointer(0)
        }
        val K_SEC_ATTR_SERVICE: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecAttrService").getPointer(0)
        }
        val K_SEC_ATTR_ACCOUNT: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecAttrAccount").getPointer(0)
        }
        val K_SEC_ATTR_SYNCHRONIZABLE: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecAttrSynchronizable").getPointer(0)
        }
        val K_SEC_ATTR_SYNCHRONIZABLE_ANY: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecAttrSynchronizableAny").getPointer(0)
        }
        val K_SEC_VALUE_DATA: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecValueData").getPointer(0)
        }
        val K_SEC_RETURN_DATA: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecReturnData").getPointer(0)
        }
        val K_SEC_RETURN_ATTRIBUTES: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecReturnAttributes").getPointer(0)
        }
        val K_SEC_MATCH_LIMIT: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecMatchLimit").getPointer(0)
        }
        val K_SEC_MATCH_LIMIT_ONE: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecMatchLimitOne").getPointer(0)
        }
        val K_SEC_MATCH_LIMIT_ALL: Pointer by lazy {
            lib.getGlobalVariableAddress("kSecMatchLimitAll").getPointer(0)
        }
    }
}
