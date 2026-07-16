package com.retryjournal.tools

import com.retryjournal.freshTestDir
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.LiveEntryIndex
import com.retryjournal.queue.record.PackedIndexEntry
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.openjdk.jol.info.GraphLayout

/**
 * Not a test — a manual, complete performance report for [DiskQueue][com.retryjournal.queue.disk.DiskQueue]:
 * how long every operation takes, and how much memory it uses/accumulates per queued request.
 *
 * There's no `me.champeau.jmh`-style JMH here — that plugin applies the plain `java` Gradle
 * plugin under the hood, which is fundamentally incompatible with a Kotlin Multiplatform module
 * that's also an Android library (verified: fails at configuration time with "the 'java' plugin
 * has been applied, but it is not compatible with the Android plugins"). The timing harness below
 * follows the same methodology JMH would (a JIT warmup phase discarded before measuring, many
 * measured iterations, reporting median/p95 instead of a single sample so GC pauses or one slow
 * disk write don't set the headline number) — real rigor, just hand-rolled for a build shape JMH
 * can't attach to.
 *
 * Run via `./gradlew :retry-journal:performanceReport` — see docs/development.md's Contributing
 * section for the reference numbers this produces and what they mean.
 */

private const val WARMUP_ITERATIONS = 200
private const val MEASURE_ITERATIONS = 500
private val SAMPLE_BODY = "{\"orderId\":\"abc-123\",\"items\":[1,2,3],\"total\":42.50}".encodeToByteArray()

fun main() = runBlocking {
    println("################ SPEED ################")
    println()
    reportSingleOperationSpeeds()
    println()
    reportScalingOperationSpeeds()
    println()
    reportDrainThroughput()

    println()
    println("################ MEMORY ################")
    println()
    reportMemoryGrowth()
    println()
    reportDrainMemoryRetention()
}

// ---------------------------------------------------------------------------------------------
// Speed
// ---------------------------------------------------------------------------------------------

private suspend fun reportSingleOperationSpeeds() {
    println("=== Single-operation cost (warmup=$WARMUP_ITERATIONS, measured=$MEASURE_ITERATIONS) ===")

    withFreshQueue("perf-enqueue") { queue ->
        benchmark("enqueue()") {
            queue.enqueue("POST", "https://example.com/orders", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
        }
    }

    withFreshQueue("perf-peek") { queue ->
        queue.enqueue("POST", "https://example.com/orders", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
        benchmark("peek()") { queue.peek() }
    }

    withFreshQueue("perf-get") { queue ->
        val id = queue.enqueue("POST", "https://example.com/orders", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
        benchmark("get(id)") { queue.get(id) }
    }

    withFreshQueue("perf-remove") { queue ->
        benchmark(
            "remove() (each iteration enqueues fresh, only remove() is timed)",
            setup = { queue.enqueue("POST", "https://example.com/orders", FrozenHttpHeaders.EMPTY, SAMPLE_BODY) },
            measure = { id -> queue.remove(id) },
        )
    }
}

private suspend fun reportScalingOperationSpeeds() {
    println("=== Cost that scales with backlog size (warmup=20, measured=100 per size) ===")
    for (n in listOf(100, 1_000, 10_000)) {
        withFreshQueue("perf-scaling-$n") { queue ->
            repeat(n) { i ->
                queue.enqueue("POST", "https://example.com/orders/$i", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
            }
            benchmark("size() at N=$n", iterations = 100, warmup = 20) { queue.size() }
            benchmark("isEmpty() at N=$n", iterations = 100, warmup = 20) { queue.isEmpty() }
        }
    }
}

private suspend fun reportDrainThroughput() {
    println(
        "=== Naive drain via the public API: isEmpty() + peek() + remove() (single run, not warmed up) ===",
    )
    println("(isEmpty()'s scrub rescans the *entire remaining* live set on every call — expect this to NOT be linear)")
    for (n in listOf(100, 1_000)) {
        withFreshQueue("perf-drain-naive-$n") { queue ->
            repeat(n) { i ->
                queue.enqueue("POST", "https://example.com/orders/$i", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
            }
            val start = System.nanoTime()
            while (!queue.isEmpty()) {
                val head = queue.peek() ?: break
                queue.remove(head.id)
            }
            val elapsedNanos = System.nanoTime() - start
            println(
                "drain $n entries (naive): total=${formatNanos(elapsedNanos)}  " +
                    "perEntry=${formatNanos(elapsedNanos / n)}",
            )
        }
    }

    println()
    println(
        "=== Realistic drain: prepareHeadForReplay()/completeHeadReplay() — what RetryJournalEngine.flush() " +
            "actually calls (single run, not warmed up) ===",
    )
    println("(scanFirstReadableHeadLocked only walks from the head until it finds one readable entry, not the whole set)")
    for (n in listOf(100, 1_000, 10_000)) {
        withFreshQueue("perf-drain-realistic-$n") { queue ->
            repeat(n) { i ->
                queue.enqueue("POST", "https://example.com/orders/$i", FrozenHttpHeaders.EMPTY, SAMPLE_BODY)
            }
            val start = System.nanoTime()
            while (true) {
                when (val prepared = queue.prepareHeadForReplay()) {
                    is HeadReplayPrepareResult.Ready -> queue.completeHeadReplay(prepared.entry.id)
                    else -> break
                }
            }
            val elapsedNanos = System.nanoTime() - start
            println(
                "drain $n entries (realistic): total=${formatNanos(elapsedNanos)}  " +
                    "perEntry=${formatNanos(elapsedNanos / n)}",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Memory (JOL)
// ---------------------------------------------------------------------------------------------

private fun reportMemoryGrowth() {
    val sizes = listOf(0, 100, 1_000, 10_000, 50_000, 100_000)

    println("=== LinkedHashMap<Long,Long> (DiskQueue.liveOffsetsBySequence's shape before the LiveEntryIndex swap) ===")
    reportGrowth(sizes) { n ->
        val map = LinkedHashMap<Long, Long>()
        for (i in 0 until n) {
            map[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        map
    }

    println()
    println(
        "=== LiveEntryIndex, built via sequential set() (organic growth — repeated enqueue(), " +
            "one at a time, no upfront size known) ===",
    )
    reportGrowth(sizes) { n ->
        val index = LiveEntryIndex()
        for (i in 0 until n) {
            index[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        index
    }

    println()
    println(
        "=== LiveEntryIndex, built via replaceAllWith() (recovery/compaction — the live count is " +
            "known upfront, so the backing array is presized exactly to it, no doubling-growth slack) ===",
    )
    reportGrowth(sizes) { n ->
        val source = LinkedHashMap<Long, Long>()
        for (i in 0 until n) {
            source[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        val index = LiveEntryIndex()
        index.replaceAllWith(source)
        index
    }
}

private fun reportDrainMemoryRetention() {
    println("=== Does draining the queue to empty (a full successful flush()) shrink the index back down? ===")
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
            "LinkedHashMap  peakN=$peakN  peakBytes=$peakBytes  bytesAfterRemovingAll=$afterDrainBytes  " +
                "freshEmptyBytes=$freshEmptyBytes  retainedSize()=${map.size}",
        )

        val index = LiveEntryIndex()
        for (i in 0 until peakN) {
            index[i.toLong()] = PackedIndexEntry.pack(length = 512, offset = i.toLong() * 512L)
        }
        val indexPeakBytes = GraphLayout.parseInstance(index).totalSize()
        for (i in 0 until peakN) {
            index.remove(i.toLong())
        }
        val indexAfterDrainBytes = GraphLayout.parseInstance(index).totalSize()
        val indexFreshEmptyBytes = GraphLayout.parseInstance(LiveEntryIndex()).totalSize()
        println(
            "LiveEntryIndex peakN=$peakN  peakBytes=$indexPeakBytes  bytesAfterRemovingAll=$indexAfterDrainBytes  " +
                "freshEmptyBytes=$indexFreshEmptyBytes  retainedSize()=${index.size}",
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

// ---------------------------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------------------------

private suspend inline fun withFreshQueue(prefix: String, block: (DiskQueue) -> Unit) {
    val dir = freshTestDir(prefix)
    val queue = DiskQueue(dir.resolve("queue.bin"))
    try {
        block(queue)
    } finally {
        queue.close()
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }
}

/** No per-iteration setup needed — [block] itself is both the setup and the measured work. */
private suspend inline fun benchmark(
    label: String,
    iterations: Int = MEASURE_ITERATIONS,
    warmup: Int = WARMUP_ITERATIONS,
    crossinline block: suspend () -> Unit,
) = benchmark(label, iterations, warmup, setup = {}, measure = { block() })

/** [setup] runs untimed before every iteration (including warmup); only [measure] is timed. */
private suspend inline fun <T> benchmark(
    label: String,
    iterations: Int = MEASURE_ITERATIONS,
    warmup: Int = WARMUP_ITERATIONS,
    crossinline setup: suspend () -> T,
    crossinline measure: suspend (T) -> Unit,
) {
    repeat(warmup) { measure(setup()) }

    val samplesNanos = LongArray(iterations)
    for (i in 0 until iterations) {
        val state = setup()
        val start = System.nanoTime()
        measure(state)
        samplesNanos[i] = System.nanoTime() - start
    }

    samplesNanos.sort()
    val medianNanos = samplesNanos[iterations / 2]
    val p95Nanos = samplesNanos[((iterations * 95) / 100).coerceAtMost(iterations - 1)]
    println("$label  median=${formatNanos(medianNanos)}  p95=${formatNanos(p95Nanos)}  (n=$iterations)")
}

private fun formatNanos(nanos: Long): String = when {
    nanos < 1_000 -> "${nanos}ns"
    nanos < 1_000_000 -> "%.2fµs".format(nanos / 1_000.0)
    else -> "%.2fms".format(nanos / 1_000_000.0)
}
