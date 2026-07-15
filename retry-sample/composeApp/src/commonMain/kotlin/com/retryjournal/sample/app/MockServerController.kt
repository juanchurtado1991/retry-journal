package com.retryjournal.sample.app

/**
 * Starts/stops the chaos server in-process, only where the platform can actually run one (a plain
 * JVM, i.e. desktop). Android and iOS have no in-process server story here, so [isSupported] is
 * `false` there and the demo falls back to pointing at a server you run yourself — see
 * retry-sample/README.md.
 */
internal expect object MockServerController {
    val isSupported: Boolean
    fun start()
    fun stop()
}
