package com.ghostserializer.sync.sample.server

internal object ChaosConstants {
    /** A request count that is an exact multiple of this triggers each chaos mode. */
    const val EVERY_NTH_OFFLINE_TIMEOUT: Int = 20
    const val EVERY_NTH_BAD_REQUEST: Int = 13
    const val EVERY_NTH_SERVICE_UNAVAILABLE: Int = 7
    const val EVERY_NTH_LATENCY: Int = 5

    /** Longer than the sample client's configured socket timeout — the client sees an IOException. */
    const val OFFLINE_TIMEOUT_DELAY_MS: Long = 15_000L

    /** Slow but within the client's timeout — a tolerable, successful delay. */
    const val LATENCY_DELAY_MS: Long = 3_000L

    const val HEALTH_CHECK_RESPONSE_BODY: String = "ok"
}
