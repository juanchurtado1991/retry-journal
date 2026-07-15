package com.retryjournal.sample.app

/** Where the chaos server (`retry-sample:server`, port [com.retryjournal.sample.shared.SampleApiConstants.DEFAULT_PORT])
 * is reachable from each platform's own network namespace — this is *not* the same address
 * everywhere. Android's emulator sits behind NAT and needs its special host alias; iOS Simulator
 * and a JVM desktop process both share the host machine's network stack directly. */
internal expect val platformServerHost: String
