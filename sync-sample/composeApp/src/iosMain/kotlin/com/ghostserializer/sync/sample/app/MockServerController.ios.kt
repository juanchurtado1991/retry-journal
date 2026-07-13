package com.ghostserializer.sync.sample.app

internal actual object MockServerController {
    actual val isSupported: Boolean = false
    actual fun start() = Unit
    actual fun stop() = Unit
}
