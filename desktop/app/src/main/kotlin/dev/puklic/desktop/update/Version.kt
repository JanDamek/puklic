package dev.puklic.desktop.update

/**
 * Puklic desktop build version. Must be kept in sync with
 * `desktop/app/build.gradle.kts` -> `compose.desktop.application.nativeDistributions.packageVersion`
 * and tagged on GitHub as `v<CURRENT>` for the auto-update check to detect releases.
 */
public object Version {
    public const val CURRENT: String = "0.1.0"
}
