package com.ghostserializer.sync.sample.server

import com.ghostserializer.sync.sample.shared.SampleApiConstants
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    embeddedServer(CIO, port = SampleApiConstants.DEFAULT_PORT, module = Application::chaosModule)
        .start(wait = true)
}
