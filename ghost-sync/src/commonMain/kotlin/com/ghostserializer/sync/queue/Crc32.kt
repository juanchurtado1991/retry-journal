package com.ghostserializer.sync.queue

/**
 * A CRC-32 is a **checksum**: you feed it a sequence of bytes and it hands back a single 32-bit
 * number that summarizes them. Feed it the exact same bytes again and you get the exact same
 * number back; change even one bit anywhere in the input and the number almost certainly comes
 * out different. It cannot repair damaged data or say *which* byte changed — it can only tell you
 * "these bytes match what was checksummed before" or "they don't," which is exactly what
 * [DiskQueue] needs: every record written to disk is checksummed, and every record read back is
 * checksummed again and compared. If a write was interrupted partway through — the process was
 * killed mid-`enqueue()` — the checksums won't match, and [DiskQueue] treats that as "this is
 * where the valid data ends," truncating the corrupt tail instead of crashing or reading garbage
 * back as if it were a real request. See [DiskQueue]'s own doc for why that recovery path matters.
 *
 * This implementation is the IEEE 802.3 variant (reflected), the same algorithm used by Ethernet,
 * gzip, and PNG — chosen for being a well-understood, widely-implemented standard rather than a
 * custom scheme. It's table-based ([TABLE]) for speed: precomputing the effect of every possible
 * byte value once means checksumming real data is one array lookup per byte instead of eight
 * bit-shifts per byte. It's written in pure Kotlin because neither the Kotlin stdlib nor Okio ship
 * a multiplatform CRC32, and `java.util.zip.CRC32` only exists on JVM/Android — this is the only
 * way to get the same corruption check on every target `:ghost-sync` compiles for, without pulling
 * in a whole dependency for what amounts to 30 lines.
 *
 * Callers accumulate across multiple byte segments (metadata, then body) with [update] and call
 * [finalize] once at the end, so a record's checksum never requires concatenating its segments
 * into one buffer first.
 *
 * **On being "as fast as possible":** this is slice-by-1 (one table lookup per byte) — table-based
 * CRC32 has a well-known faster variant, slice-by-8/16, that processes 8-16 bytes per iteration
 * against 8-16 precomputed tables instead of 1. It's a real speedup, but only for large buffers
 * (multi-KB+); at the record sizes `:ghost-sync` actually checksums — a small metadata struct plus
 * a typical HTTP mutation body, rarely more than a few KB — the bigger table (8-16x the 1 KB this
 * one uses) and extra branching mostly just cost more L1 cache footprint for no measurable win.
 * True hardware-accelerated CRC32 (the x86 `CRC32` / ARMv8 `CRC32` instructions) is off the table
 * entirely: it computes CRC32**C** (the Castagnoli polynomial), not IEEE 802.3, and reaching it
 * from Kotlin needs per-platform native code — which is exactly the dependency this class exists
 * to avoid. Slice-by-1 is the practical ceiling for a pure-Kotlin, multiplatform, small-record
 * checksum; if `:ghost-sync` ever needs to checksum large payloads (bulk file bodies, not typical
 * JSON mutations), slice-by-8 would be the next lever to pull, not before.
 */
internal object Crc32 {

    const val INITIAL_VALUE: Int = -1 // 0xFFFFFFFF

    private const val POLYNOMIAL: Int = -0x12477ce0 // 0xEDB88320, reflected IEEE 802.3 polynomial
    private const val BYTE_VALUE_COUNT: Int = 256
    private const val BITS_PER_BYTE: Int = 8
    private const val LONG_HIGH_BYTE_SHIFT: Int = 56
    private const val BYTE_MASK: Int = 0xFF
    private const val LONG_BYTE_MASK: Long = 0xFFL

    private val TABLE: IntArray = IntArray(BYTE_VALUE_COUNT).also { table ->
        for (byteValue in 0 until BYTE_VALUE_COUNT) {
            var accumulator = byteValue
            repeat(BITS_PER_BYTE) {
                accumulator = if (accumulator and 1 != 0) {
                    (accumulator ushr 1) xor POLYNOMIAL
                } else {
                    accumulator ushr 1
                }
            }
            table[byteValue] = accumulator
        }
    }

    fun update(crc: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var accumulator = crc
        val end = offset + length
        for (byteIndex in offset until end) {
            accumulator = updateByte(
                accumulator,
                bytes[byteIndex].toInt() and BYTE_MASK
            )
        }
        return accumulator
    }

    fun updateLong(crc: Int, value: Long): Int {
        var accumulator = crc
        for (shift in LONG_HIGH_BYTE_SHIFT downTo 0 step BITS_PER_BYTE) {
            val byteValue = ((value ushr shift) and LONG_BYTE_MASK).toInt()
            accumulator = updateByte(accumulator, byteValue)
        }
        return accumulator
    }

    fun finalize(crc: Int): Int = crc.inv()

    private fun updateByte(crc: Int, byteValue: Int): Int {
        val tableIndex = (crc xor byteValue) and BYTE_MASK
        return (crc ushr BITS_PER_BYTE) xor TABLE[tableIndex]
    }
}
