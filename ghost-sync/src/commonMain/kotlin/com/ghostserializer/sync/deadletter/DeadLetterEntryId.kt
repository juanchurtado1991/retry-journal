package com.ghostserializer.sync.deadletter

import kotlin.jvm.JvmInline

/**
 * Distinct from [com.ghostserializer.sync.queue.QueueEntryId] on purpose: a dead-letter entry
 * lives in a different backing file with its own sequence-id space, so the two ids must never be
 * interchangeable — passing a main-queue id into [DeadLetterQueue] (or vice versa) would silently
 * miss or, worse, coincidentally match the wrong record. Compiles to a raw `Long`.
 */
@JvmInline
value class DeadLetterEntryId(val value: Long)
