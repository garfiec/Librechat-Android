import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {

    // A UIKit app delegate is needed so we can supply a scene delegate that receives Home-Screen
    // quick-action taps (SwiftUI's App lifecycle doesn't surface UIApplicationShortcutItem directly).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Initialize Koin DI — must happen before any KMP code is used
        IosKoinHelperKt.startIosKoin()
    }

    var body: some Scene {
        WindowGroup {
            LibreChatComposeView()
                .ignoresSafeArea()
        }
        .onChange(of: scenePhase) { phase in
            // Refresh the most-used-model quick actions as the app leaves the foreground, so a
            // long-press on the app icon reflects the latest usage.
            if phase == .background {
                ModelQuickActions.refresh()
            }
        }
    }
}

/// Supplies a scene delegate; that's the object iOS delivers quick-action taps to.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

/// Handles Home-Screen quick actions both at cold launch (`willConnectTo`) and while running
/// (`performActionFor`). It only observes scene events — it never creates a window, so SwiftUI's
/// `WindowGroup` still owns the UI.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        if let shortcutItem = connectionOptions.shortcutItem {
            ModelQuickActions.handle(shortcutItem)
        }
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(ModelQuickActions.handle(shortcutItem))
    }
}

/// Bridges the KMP most-used-models ranking to iOS Home-Screen quick actions.
enum ModelQuickActions {

    /// Single shortcut type; the concrete model rides in `userInfo` so one handler covers all.
    private static let itemType = "com.garfiec.librechat.model"

    /// Rebuilds `UIApplication.shortcutItems` from the account's most-used models. An empty list
    /// (logged out / no usage yet) clears them.
    static func refresh() {
        Task {
            guard let models = try? await IosKoinAccessor.shared.currentTopModels(limit: 4) else { return }
            let items = models.map { ref in
                UIApplicationShortcutItem(
                    type: itemType,
                    localizedTitle: ref.model,
                    localizedSubtitle: nil,
                    icon: UIApplicationShortcutIcon(type: .message),
                    userInfo: [
                        "endpoint": ref.endpoint as NSString,
                        "model": ref.model as NSString,
                    ]
                )
            }
            await MainActor.run {
                UIApplication.shared.shortcutItems = items
            }
        }
    }

    /// Routes a tapped quick action into the shared navigation host. Returns whether it was ours.
    @discardableResult
    static func handle(_ shortcutItem: UIApplicationShortcutItem) -> Bool {
        guard shortcutItem.type == itemType,
              let endpoint = shortcutItem.userInfo?["endpoint"] as? String,
              let model = shortcutItem.userInfo?["model"] as? String else {
            return false
        }
        try? IosKoinAccessor.shared.requestModelShortcut(endpoint: endpoint, model: model)
        return true
    }
}
