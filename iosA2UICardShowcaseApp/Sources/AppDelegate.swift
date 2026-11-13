// A2UI Card Showcase Demo App (iOS)
// -----------------------------------
// This is a STANDALONE iOS app that mocks an "AI Chat" interface and three
// A2UI-style card scenarios. It does NOT depend on the A2UI or KuiklyUI
// libraries at all — everything here is plain UIKit + local mock data.
//
// The app is intended for expectation-demo / screen-recording purposes only.

import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        let chat = ChatViewController()
        let nav = UINavigationController(rootViewController: chat)
        nav.navigationBar.tintColor = AppTheme.primary
        nav.navigationBar.prefersLargeTitles = false
        // Make the nav bar blend with the chat background for a clean look.
        let appearance = UINavigationBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = AppTheme.background
        appearance.shadowColor = .clear
        appearance.titleTextAttributes = [
            .foregroundColor: AppTheme.textPrimary,
            .font: UIFont.systemFont(ofSize: 17, weight: .semibold),
        ]
        nav.navigationBar.standardAppearance = appearance
        nav.navigationBar.scrollEdgeAppearance = appearance

        window?.rootViewController = nav
        window?.makeKeyAndVisible()
        return true
    }
}
