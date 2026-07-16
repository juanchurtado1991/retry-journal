package com.retryjournal.tools

import com.retryjournal.queue.record.PackedIndexEntry
import org.openjdk.jol.info.GraphLayout

/**
 * Not a test — a manual diagnostic for [DiskQueue][com.retryjournal.queue.disk.DiskQueue]'s
 * in-memory `liveOffsetsBySequence` index (a `LinkedHashMap<Long, Long>` held for the lifetime of
 * every open queue, one entry per still-queued request). Measures its *actual* retained memory
 * with JOL (Java Object Layout) instead of guessing from Java's nominal per-object sizes, at
 * increasing backlog sizes, and compares it against a flat `LongArray` (the shape a future
 * zero-allocation redesign would use) as a reference point for how much headroom exists.
 *
 * Run via `./gradlew :retry-journal:memoryProbe` — see docs/development.md's Contributing section
 * for the reference numbers this produces and what they mean.
 */
fun main() {
    val sizes = listOf(0, 100, 1_000, 10_000, 50_000, 100_000)

    println("=== LinkedHashMap<Long,Long> (DiskQueue.liveOffsetsBySequence's current shape) ===")
    reportGrowth(sizes) { n ->
        val map = LinkedHashMap<Long, Long>()
        for (i in 0 until n) {
            map[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        map
    }

    println()
    println("=== LongArray (dense, no stored key — a possible zero-allocation redesign) ===")
    reportGrowth(sizes) { n ->
        LongArray(n) { PackedIndexEntry.pack(length = 512, offset = it.toLong() * 512L) }
    }

    println()
    println("=== Does draining the queue to empty (a full successful flush()) shrink the map back down? ===")
    for (peakN in listOf(1_000, 10_000, 100_000)) {
        val map = LinkedHashMap<Long, Long>()
        for (i in 0 until peakN) {
            map[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        val peakBytes = GraphLayout.parseInstance(map).totalSize()
        for (i in 0 until peakN) {
            map.remove(i.toLong())
        }
        val afterDrainBytes = GraphLayout.parseInstance(map).totalSize()
        val freshEmptyBytes = GraphLayout.parseInstance(LinkedHashMap<Long, Long>()).totalSize()
        println(
            "peakN=$peakN  peakBytes=$peakBytes  bytesAfterRemovingAll=$afterDrainBytes  " +
                "freshEmptyMapBytes=$freshEmptyBytes  retainedSize()=${map.size}",
        )
    }
}

private fun reportGrowth(sizes: List<Int>, build: (Int) -> Any) {
    var previousBytes = 0L
    var previousN = 0
    for (n in sizes) {
        val instance = build(n)
        val totalBytes = GraphLayout.parseInstance(instance).totalSize()
        val marginalPerEntry = if (n > previousN) (totalBytes - previousBytes).toDouble() / (n - previousN) else 0.0
        println("N=$n  totalBytes=$totalBytes  marginalBytesPerEntry=${"%.2f".format(marginalPerEntry)}")
        previousBytes = totalBytes
        previousN = n
    }
}
