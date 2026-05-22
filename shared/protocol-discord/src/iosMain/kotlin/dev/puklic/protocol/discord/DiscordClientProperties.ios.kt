package dev.puklic.protocol.discord

import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.countryCode
import platform.Foundation.localTimeZone
import platform.Foundation.name
import platform.Foundation.operatingSystemVersionString

public actual fun currentHostEnvironment(): HostEnvironment {
    val locale = NSLocale.currentLocale
    val language = locale.languageCode
    val country = locale.countryCode
    val tag = if (!country.isNullOrEmpty()) "$language-$country" else language
    return HostEnvironment(
        osName = "Mac OS X",
        osVersion = NSProcessInfo.processInfo.operatingSystemVersionString,
        locale = tag,
    )
}

public actual fun currentTimeZoneId(): String = NSTimeZone.localTimeZone.name
