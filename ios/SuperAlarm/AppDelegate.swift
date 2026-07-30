import AVFoundation
import AudioToolbox
import CoreImage.CIFilterBuiltins
import Security
import SwiftUI
import UIKit
import UserNotifications
import VisionKit

@main
struct SuperAlarmApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .onAppear {
                    appDelegate.connect(store: store)
                    store.start()
                }
                .onOpenURL { url in
                    store.handleDeepLink(url)
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private weak var store: AppStore?

    func connect(store: AppStore) {
        self.store = store
        UNUserNotificationCenter.current().delegate = self
        NotificationLaunchBridge.shared.onLaunch = { [weak store] identifier in
            let kind: AlarmAttempt.Kind = identifier == AlarmNotificationService.onboardingTestIdentifier ? .test : .live
            store?.openRinging(kind: kind, notificationIdentifier: identifier)
        }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .list, .sound])
        } else {
            completionHandler([.alert, .sound])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        NotificationLaunchBridge.shared.open(identifier: response.notification.request.identifier)
        completionHandler()
    }
}

final class NotificationLaunchBridge {
    static let shared = NotificationLaunchBridge()
    var onLaunch: ((String) -> Void)?

    func open(identifier: String) {
        DispatchQueue.main.async { self.onLaunch?(identifier) }
    }
}

// MARK: - Models
