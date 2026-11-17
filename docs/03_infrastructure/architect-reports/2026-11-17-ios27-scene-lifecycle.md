# iOS 27 scene lifecycle adoption (2026-11-17)

Crash (TestFlight 1.2.26, iOS 27.0.1): `EXC_BREAKPOINT` in
`___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke` right after launch.
iOS 27 aborts apps that declare a `UIApplicationSceneManifest` but provide no scene delegate.

Fix: `AppDelegate` keeps only the `IosDependencyGraph` and returns a `UISceneConfiguration`;
the new `SceneDelegate` creates the `UIWindow` from the `UIWindowScene` and installs the Compose root
view controller. `UISceneConfigurations` is declared in `Info.plist` and `project.yml`.
