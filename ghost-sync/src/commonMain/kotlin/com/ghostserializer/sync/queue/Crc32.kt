package com.ghostserializer.sync.queue

/**
 * IEEE 802.3 CRC-32 (reflected), table-based. Pure Kotlin: neither the stdlib nor Okio ship a
 * multiplatform CRC32, and `java.util.zip.CRC32` is JVM/Android-only, so this is the only way to
 * get the same corruption check on every target without pulling in a dependency for 30 lines.
 *
 * Callers accumulate across multiple byte segments (metadata, then body) with [update] and call
 * [finalize] once at the end, so a record's checksum never requires concatenating its segments
 * into one buffer first.
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
            accumulator = updateByte(accumulator, bytes[byteIndex].toInt() and BYTE_MASK)
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
