package dev.puklic.platform

/**
 * Base type for every failure surfaced by a [:shared:platform-api] interface.
 * Platform-native exceptions MUST be wrapped before crossing the API boundary.
 */
sealed class PlatformException(message: String) : Exception(message)

/** The requested capability is not available on the current platform. */
class PlatformUnavailable(message: String) : PlatformException(message)

/** The user (or the OS policy) denied permission for the operation. */
class PlatformDenied(message: String) : PlatformException(message)

/** The operation reached the OS but failed; [message] carries the native detail. */
class PlatformFailed(message: String) : PlatformException(message)
