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

    var window: UIWindow?
    /// Retained for the app's lifetime — owns the application coroutine scope, the SQLite
    /// connection, the HTTP client and the Discord session manager.
    private var graph: IosDependencyGraph?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let graph = IosDependencyGraph.companion.create()
        self.graph = graph

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = graph.puklicAppRootViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
