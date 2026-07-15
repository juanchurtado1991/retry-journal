package com.retryjournal.sample.app

/** A JVM desktop process shares the host machine's network stack directly — no NAT alias needed. */
internal actual val platformServerHost: String = "localhost"
