// Reference only — see README.md in this folder. Adapted from kmpworkmanager's own README;
// not independently compiled/verified (no Xcode on the machine this was written on).
import UIKit
import BackgroundTasks
import ComposeApp

private let ghostSyncBackgroundTaskId = "ghost_sync_task"

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    override init() {
        super.init()
        KoinInitializerKt.doInitKoin(platformModule: IOSModuleKt.iosModule)
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let koin = KoinIOS()

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: ghostSyncBackgroundTaskId,
            using: nil
        ) { task in
            IosBackgroundTaskHandler.shared.handleChainExecutorTask(
                task: task,
                chainExecutor: koin.getChainExecutor()
            )
        }
        return true
    }
}
