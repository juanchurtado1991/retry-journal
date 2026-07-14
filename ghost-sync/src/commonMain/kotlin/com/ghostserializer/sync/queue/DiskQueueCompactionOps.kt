package com.ghostserializer.sync.queue

/** Applies a [DiskQueueCompactionPlan] produced by [DiskQueueCompactor.planCompaction]. */
internal object DiskQueueCompactionOps {

    fun compactIfNeededLocked(queue: DiskQueue) {
        val claimPath = ReplayClaim.claimPath(queue.path)
        if (ReplayClaim.hasNonStaleClaim(queue.fileSystem, claimPath) != null) {
            return
        }
        val plan = DiskQueueCompactor.planCompaction(
            queue.fileSystem,
            queue.path,
            queue.maxRecordFieldSize,
            queue.liveOffsetsBySequence,
            queue.fileLength,
            queue.deadBytes,
            queue.nextSequenceId,
        ) ?: return

        applyPlanLocked(queue, plan)
    }

    fun applyPlanLocked(queue: DiskQueue, plan: DiskQueueCompactionPlan) {
        queue.fileHandles.closeAppendSink()
        queue.fileHandles.closeReadHandle()
        queue.fileSystem.atomicMove(plan.tempPath, queue.path)

        queue.liveOffsetsBySequence.clear()
        queue.liveOffsetsBySequence.putAll(plan.liveOffsetsBySequence)
        queue.fileLength = plan.fileLength
        queue.deadBytes = 0L
        DiskQueueIndexSync.bumpGenerationLocked(queue)
    }
}

internal fun DiskQueue.compactIfNeededLocked() =
    DiskQueueCompactionOps.compactIfNeededLocked(this)

internal fun DiskQueue.applyCompactionPlanLocked(plan: DiskQueueCompactionPlan) =
    DiskQueueCompactionOps.applyPlanLocked(this, plan)
