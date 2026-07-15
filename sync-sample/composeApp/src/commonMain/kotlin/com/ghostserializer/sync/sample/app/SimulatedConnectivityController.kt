package com.ghostserializer.sync.sample.app

/**
 * In-process connectivity override for mobile platforms (iOS / Android) where the
 * demo cannot start/stop a real server. Toggling this simulates the device going
 * offline: the GhostSyncRuntime stops auto-flushing, new HTTP requests are
 * intercepted and written to the disk queue, and when [setOnline] is called again
 * the runtime triggers an automatic replay.
 *
 * Desktop keeps using [MockServerController] (actual server on/off).
 */
internal object SimulatedConnectivityController {
    private var _isSimulatedOffline = false
    val isSimulatedOffline: Boolean get() = _isSimulatedOffline

    fun setOnline() {
        _isSimulatedOffline = false
        SyncSetup.reportConnectivity(online = true)
    }

    fun setOffline() {
        _isSimulatedOffline = true
        SyncSetup.reportConnectivity(online = false)
    }
}
