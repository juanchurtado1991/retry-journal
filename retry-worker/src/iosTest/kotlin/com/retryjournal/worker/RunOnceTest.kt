package com.retryjournal.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RegisterRetryJournalBackgroundTask]'s `handleBackgroundTask` can't be unit tested directly —
 * `BGAppRefreshTask` is an Apple framework type with no test double API — but [RunOnce] is the
 * exact concurrency primitive it relies on to guard against `expirationHandler` and the run's own
 * completion both finishing the same `BGTask`, and that primitive is fully testable on its own. */
class RunOnceTest {

    @Test
    fun testRunOnceRunsExactlyOnceUnderConcurrentCallers() = runBlocking {
        val runOnce = RunOnce()
        val callCount = AtomicInt(0)

        coroutineScope {
            val jobs = List(50) {
                async(Dispatchers.Default) {
                    runOnce.runOnce { callCount.addAndGet(1) }
                }
            }
            jobs.awaitAll()
        }

        assertEquals(1, callCount.value)
    }

    @Test
    fun testRunOnceRunsForASingleCaller() {
        val runOnce = RunOnce()
        var ran = false
        runOnce.runOnce { ran = true }
        assertEquals(true, ran)
    }

    @Test
    fun testASecondCallIsANoOp() {
        val runOnce = RunOnce()
        var count = 0
        runOnce.runOnce { count++ }
        runOnce.runOnce { count++ }
        assertEquals(1, count)
    }
}
