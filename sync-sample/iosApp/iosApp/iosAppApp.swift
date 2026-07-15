import SwiftUI
import BackgroundTasks
import ComposeApp


class AppDelegate: UIResponder, UIApplicationDelegate {
    override init() {
        super.init()
        KmpWorkManagerHelper.shared.initialize()
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "ghost_sync_task",
            using: nil
        ) { task in
            KmpWorkManagerHelper.shared.handleBackgroundTask(task: task)
        }
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "kmp_chain_executor_task",
            using: nil
        ) { task in
            KmpWorkManagerHelper.shared.handleBackgroundTask(task: task)
        }
        return true
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
