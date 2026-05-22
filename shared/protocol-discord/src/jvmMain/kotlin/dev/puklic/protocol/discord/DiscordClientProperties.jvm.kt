package dev.puklic.protocol.discord

import java.util.Locale

public actual fun currentHostEnvironment(): HostEnvironment = HostEnvironment(
    osName = System.getProperty("os.name"),
    osVersion = System.getProperty("os.version"),
    locale = Locale.getDefault().toLanguageTag(),
)

public actual fun currentTimeZoneId(): String = java.util.TimeZone.getDefault().id
