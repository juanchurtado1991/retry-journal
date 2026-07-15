package com.retryjournal.sample.server

internal object ServerConstants {
    /** Program argument (`--args="chaos"` via Gradle) that switches [main] from [normalModule]
     * to [chaosModule]. */
    const val CHAOS_FLAG: String = "chaos"
}
