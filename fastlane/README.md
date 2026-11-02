fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## iOS

### ios beta

```sh
[bundle exec] fastlane ios beta
```

Build iOS framework, archive, export, and upload to TestFlight (internal).

### ios build_only

```sh
[bundle exec] fastlane ios build_only
```

Archive iOS app to ../build/ios-archive/Puklic.ipa (no upload).

### ios upload_only

```sh
[bundle exec] fastlane ios upload_only
```

Upload an existing ../build/ios-archive/Puklic.ipa to TestFlight (internal).

### ios asc_ping

```sh
[bundle exec] fastlane ios asc_ping
```

Smoke-check ASC API connectivity (no upload).

----


## Mac

### mac mac_app_store

```sh
[bundle exec] fastlane mac mac_app_store
```

Build, sign, package, and upload Puklic to Mac App Store.

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
