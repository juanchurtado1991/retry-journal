package com.ghostserializer.sync.queue

import kotlin.jvm.JvmInline

/**
 * A monotonically increasing id written into the record itself — not the record's byte offset.
 * Compaction relocates records to reclaim dead space, which would silently invalidate an
 * offset-based id (and either leak the entry forever or, worse, delete whatever unrelated
 * record ends up at that stale offset). A persisted sequence id survives both compaction and
 * process restarts. Compiles to a raw `Long` — no wrapper allocation.
 */
@JvmInline
value class QueueEntryId(val sequenceId: Long)
