package com.ghostserializer.sync.queue.record

import com.ghostserializer.sync.queue.disk.DiskQueueConstants.INDEX_OFFSET_BITS

/** Packs a record's on-disk length and byte offset into the single [Long]
 * [DiskQueue][com.ghostserializer.sync.queue.disk.DiskQueue] keeps per live sequence id — see
 * [DiskQueue][com.ghostserializer.sync.queue.disk.DiskQueue]'s own `liveOffsetsBySequence` doc for the
 * bit layout. */
internal object PackedIndexEntry {
    private const val OFFSET_MASK: Long = (1L shl INDEX_OFFSET_BITS) - 1L

    fun pack(length: Int, offset: Long): Long = (length.toLong() shl INDEX_OFFSET_BITS) or offset
    fun unpackLength(packed: Long): Int = (packed ushr INDEX_OFFSET_BITS).toInt()
    fun unpackOffset(packed: Long): Long = packed and OFFSET_MASK
}
