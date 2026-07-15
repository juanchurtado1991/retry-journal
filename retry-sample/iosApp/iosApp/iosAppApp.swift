import SwiftUI
import ComposeApp

/// `RetryJournalBackgroundSetup.shared.register()` (`:retry-worker`'s iOS scheduler) registers
/// the `BGTaskScheduler` launch handler itself — this delegate only has to call it early enough.
/// Per Apple's rule, task registration must complete synchronously before
/// `application(_:didFinishLaunchingWithOptions:)` returns, so it happens in `init()`.
class AppDelegate: UIResponder, UIApplicationDelegate {
    override init() {
        super.init()
        RetryJournalBackgroundSetup.shared.register()
    }
}

@main
struct iosAppApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
