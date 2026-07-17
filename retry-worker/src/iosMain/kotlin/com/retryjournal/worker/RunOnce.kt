package com.retryjournal.worker

import kotlin.concurrent.AtomicInt

/** Runs its action at most once across however many concurrent callers race to invoke it —
 * [runOnce] is a compare-and-set, not a plain flag check, so it's safe to call from multiple
 * threads at once (unlike a `@Volatile var` read-then-write, which two threads can both pass).
 * Used by [handleBackgroundTask] to guard against `expirationHandler` and the run's own
 * completion both trying to finish the same `BGTask`. */
internal class RunOnce {
    private val finished = AtomicInt(0)

    fun runOnce(action: () -> Unit) {
        if (finished.compareAndSet(0, 1)) {
            action()
        }
    }
}
