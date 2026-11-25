package dev.puklic.platform.android

import android.content.Context

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

/** Authority of the `FileProvider` declared in this library's manifest (`${applicationId}.fileprovider`). */
internal fun Context.fileProviderAuthority(): String = packageName + FILE_PROVIDER_SUFFIX
