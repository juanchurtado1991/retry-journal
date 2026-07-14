package com.ghostserializer.sync.engine

/** Outcome of [GhostSyncEngine.finalizeHeadReplay] for manual replay loops. */
sealed class HeadFinalizeResult {
    data object Delivered : HeadFinalizeResult()

    data object DeadLettered : HeadFinalizeResult()

    /** 5xx / retry-worthy 4xx — entry left on the main queue. */
    data object LeftOnQueue : HeadFinalizeResult()

    data class PersistenceFailed(val persistenceFailed: Boolean = true) : HeadFinalizeResult()
}
