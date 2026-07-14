package com.ghostserializer.sync.queue

/** Parsed contents of a cross-process [ReplayClaim] marker file. */
internal data class ReplayClaimActive(
    val sequenceId: Long,
    val claimedAtMillis: Long,
)
