package com.retryjournal.worker

import com.retryjournal.scheduler.RetryJournalScheduler
import com.retryjournal.scheduler.RetryJournalSchedulerConfig

/**
 * [RetryJournalScheduler] for JVM/desktop targets, where there is no OS-standardized background
 * scheduler to hook into. [schedule] and [cancel] are no-ops — desktop apps typically drive
 * `flush()` from their own timer or a foreground coroutine loop instead.
 */
class NoOpRetryJournalWorkerScheduler : RetryJournalScheduler {
    override fun schedule(config: RetryJournalSchedulerConfig) = Unit

    override fun cancel() = Unit
}
