import SwiftUI
import UserNotifications

@main
struct IBridgeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var store: Store
    @State private var central: Central?
    private let firstTab: Int

    init() {
        let store = Store()
        let demo = Demo.scenario()
        if let demo { Demo.apply(demo, to: store) } else { store.load() }
        _store = State(initialValue: store)
        firstTab = demo?.tab ?? 0
        RootView.styleTabBar()
        if demo == nil {
            let c = Central(store: store)
            _central = State(initialValue: c)
            AppDelegate.central = c
            Notify.setUp()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView(tab: firstTab)
                .environment(store)
                .environment(\.central, central)
                .preferredColorScheme(.light)
        }
    }
}

private struct CentralKey: EnvironmentKey {
    static let defaultValue: Central? = nil
}

extension EnvironmentValues {
    var central: Central? {
        get { self[CentralKey.self] }
        set { self[CentralKey.self] = newValue }
    }
}

/// Notification actions on an incoming call: Decline, or Text back.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor static var central: Central?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let call = info["call"] as? String else { return }
        let from = response.notification.request.content.title
        await MainActor.run {
            switch response.actionIdentifier {
            case "decline":
                Self.central?.declineCall(call)
            case "reply":
                if let text = (response as? UNTextInputNotificationResponse)?.userText, !text.isEmpty {
                    Self.central?.declineCall(call)
                    _ = Self.central?.sendText(to: from, body: text)
                }
            default: break
            }
        }
    }
}
