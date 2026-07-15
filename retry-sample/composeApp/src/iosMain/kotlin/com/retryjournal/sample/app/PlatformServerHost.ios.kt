package com.retryjournal.sample.app

/** Unlike the Android emulator, iOS Simulator shares the host machine's network stack directly —
 * no NAT alias needed. On a physical device, override with your machine's LAN IP instead. */
internal actual val platformServerHost: String = "localhost"
