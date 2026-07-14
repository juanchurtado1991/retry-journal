package com.ghostserializer.sync.engine

/** Internal flush-loop control flow for [GhostSyncEngine] — continue draining or stop early. */
internal sealed class ReplayOutcome {
    data class Continue(val delivered: Int, val deadLettered: Int) : ReplayOutcome()
    data class Stop(val delivered: Int, val deadLettered: Int) : ReplayOutcome()
}
