package com.retryjournal.scheduler

/** Tuning knobs for a [RetryJournalScheduler] implementation — periodicity, network requirement, retry policy. */
data class RetryJournalSchedulerConfig(
    val intervalMs: Long = RetryJournalSchedulerConfigDefaults.INTERVAL_MS,
    val requiresNetwork: Boolean = true,
    val retryDelayMs: Long = RetryJournalSchedulerConfigDefaults.RETRY_DELAY_MS,
    val maxRetryAttempts: Int = RetryJournalSchedulerConfigDefaults.MAX_RETRY_ATTEMPTS,
)

internal object RetryJournalSchedulerConfigDefaults {
    const val INTERVAL_MS: Long = 15 * 60 * 1_000L
    const val RETRY_DELAY_MS: Long = 60_000L
    const val MAX_RETRY_ATTEMPTS: Int = 5
}
