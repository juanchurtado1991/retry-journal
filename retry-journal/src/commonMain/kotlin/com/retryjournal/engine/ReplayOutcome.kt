package com.retryjournal.engine

/** Internal flush-loop control flow for [RetryJournalEngine] — continue draining or stop early. */
internal sealed class ReplayOutcome {
    data class Continue(val delivered: Int, val deadLettered: Int) : ReplayOutcome()
    data class Stop(
        val delivered: Int,
        val deadLettered: Int,
        val persistenceFailed: Boolean = false,
    ) : ReplayOutcome()
}
