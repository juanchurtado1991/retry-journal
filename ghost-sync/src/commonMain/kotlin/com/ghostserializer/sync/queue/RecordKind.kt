package com.ghostserializer.sync.queue

/**
 * One byte on disk. [Tombstone] never overwrites the record it retires — it is appended after
 * it, referencing its offset, so `remove()` never rewrites a header (the failure mode that made
 * square/tape prone to corruption on abrupt shutdown).
 */
internal enum class RecordKind(val byteValue: Byte) {
    Live(1),
    Tombstone(2);

    companion object {
        fun fromByte(value: Byte): RecordKind? =
            entries.firstOrNull { it.byteValue == value }
    }
}
