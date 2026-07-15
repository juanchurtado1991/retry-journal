package com.retryjournal.sample.app

import com.retryjournal.sample.server.normalModule
import com.retryjournal.sample.shared.SampleApiConstants
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer

private const val STOP_GRACE_PERIOD_MS: Long = 0L
private const val STOP_TIMEOUT_MS: Long = 200L

internal actual object MockServerController {
    private var server: ApplicationEngine? = null

    actual val isSupported: Boolean = true

    @Synchronized
    actual fun start() {
        if (server != null) {
            return
        }
        server = embeddedServer(CIO, port = SampleApiConstants.DEFAULT_PORT, module = Application::normalModule)
            .start(wait = false)
    }

    @Synchronized
    actual fun stop() {
        server?.stop(STOP_GRACE_PERIOD_MS, STOP_TIMEOUT_MS)
        server = null
    }
}
