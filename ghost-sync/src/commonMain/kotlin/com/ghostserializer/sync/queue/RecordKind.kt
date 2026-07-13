package com.ghostserializer.sync.queue

// Kept file-level, not inside RecordKind's companion object: enum entries are constructed before
// their companion object is initialized, so a companion const val isn't visible to Live(...)/
// Tombstone(...) below — the Kotlin compiler rejects it outright.
private const val LIVE_BYTE_VALUE: Byte = 1
private const val TOMBSTONE_BYTE_VALUE: Byte = 2

/**
 * One byte on disk. [Tombstone] never overwrites the record it retires — it is appended after
 * it, referencing its offset, so `remove()` never rewrites a header (the failure mode that made
 * square/tape prone to corruption on abrupt shutdown).
 *
 * Byte values start at 1, not 0, on purpose: an all-zero region of a file (a truncated write, an
 * unwritten/sparse gap) then reads as an unrecognized kind, so [fromByte] returns `null` and
 * [RecordCodec] treats it as [RecordReadResult.Invalid] instead of silently misreading zeroed
 * bytes as a real record.
 */
internal enum class RecordKind(val byteValue: Byte) {
    Live(LIVE_BYTE_VALUE),
    Tombstone(TOMBSTONE_BYTE_VALUE),
    ;

    companion object {
        fun fromByte(value: Byte): RecordKind? =
            entries.firstOrNull { it.byteValue == value }
    }
}
