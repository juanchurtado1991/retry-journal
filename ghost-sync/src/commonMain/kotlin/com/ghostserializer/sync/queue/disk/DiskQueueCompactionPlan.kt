package com.ghostserializer.sync.queue.disk

import okio.Path

/** Output of [DiskQueueCompactor.planCompaction] — a temp file ready for atomic swap onto the
 * live queue path, plus the rebuilt live index. */
internal class DiskQueueCompactionPlan(
    val tempPath: Path,
    val liveOffsetsBySequence: LinkedHashMap<Long, Long>,
    val fileLength: Long,
)
