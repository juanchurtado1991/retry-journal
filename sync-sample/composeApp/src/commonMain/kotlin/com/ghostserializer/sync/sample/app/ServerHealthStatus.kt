package com.ghostserializer.sync.sample.app

/** Result of the last poll of the chaos server's `/health` endpoint — drives the banner at the
 * top of the screen so it's obvious, without reading logs, whether "enqueue offline" will really
 * go offline or just hit the live server. */
internal enum class ServerHealthStatus { Checking, Online, Offline }
