import UIKit

/// Owns the `UIWindow` of the single Puklic scene. UIKit scene lifecycle is mandatory
/// since iOS 27 (an app without a scene delegate is aborted at launch); the dependency
/// graph itself stays owned by `AppDelegate`.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene,
              let appDelegate = UIApplication.shared.delegate as? AppDelegate,
              let graph = appDelegate.graph else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = graph.puklicAppRootViewController()
        window.makeKeyAndVisible()
        self.window = window
    }
}
