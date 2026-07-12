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

    private val TABLE: IntArray = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) {
                    (c ushr 1) xor POLYNOMIAL
                } else {
                    c ushr 1
                }
            }
            table[n] = c
        }
    }

    fun update(crc: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        var c = crc
        val end = offset + length
        for (i in offset until end) {
            c = updateByte(c, bytes[i].toInt() and 0xFF)
        }
        return c
    }

    fun updateLong(crc: Int, value: Long): Int {
        var c = crc
        for (shift in 56 downTo 0 step 8) {
            val byteValue = ((value ushr shift) and 0xFFL).toInt()
            c = updateByte(c, byteValue)
        }
        return c
    }

    fun finalize(crc: Int): Int = crc.inv()

    private fun updateByte(crc: Int, byteValue: Int): Int {
        val index = (crc xor byteValue) and 0xFF
        return (crc ushr 8) xor TABLE[index]
    }
}
