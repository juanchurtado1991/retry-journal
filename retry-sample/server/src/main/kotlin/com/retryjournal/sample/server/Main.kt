package com.retryjournal.sample.server

import com.retryjournal.sample.shared.SampleApiConstants
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/** Defaults to [normalModule] (no artificial failures) — pass `--args="chaos"` via Gradle
 * (`./gradlew :retry-sample:server:run --args="chaos"`) to switch to [chaosModule]. */
fun main(args: Array<String>) {
    val module: Application.() -> Unit = if (args.contains(ServerConstants.CHAOS_FLAG)) {
        Application::chaosModule
    } else {
        Application::normalModule
    }
    embeddedServer(CIO, port = SampleApiConstants.DEFAULT_PORT, module = module).start(wait = true)
}
