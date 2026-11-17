import UIKit
import PuklicShared

/// Puklic iOS app entry. Instantiates `IosDependencyGraph` once at app launch and
/// installs the Compose-hosted view controller as the window's root.
///
/// Architecture: the entire DI graph (SQLite, Ktor Darwin, Discord session, resolvers,
/// session manager + auto-restore) lives in Kotlin (`:ios:app/.../IosDependencyGraph.kt`).
/// Swift owns only the `UIWindow` + the `IosDependencyGraph` reference so the graph's
/// application coroutine scope outlives every screen transition.
///
/// See:
///   docs/03_infrastructure/architect-reports/2026-05-28-ios-dependency-graph.md
///   docs/03_infrastructure/architect-reports/2026-05-28-ios-app-framework.md
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    /// Retained for the app's lifetime — owns the application coroutine scope, the SQLite
    /// connection, the HTTP client and the Discord session manager.
    private(set) var graph: IosDependencyGraph?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        graph = IosDependencyGraph.companion.create()
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}
