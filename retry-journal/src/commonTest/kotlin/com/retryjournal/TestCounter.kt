package com.retryjournal

/** A thread-safe counter for tests that race real coroutines against each other. */
expect class TestCounter(initial: Int = 0) {
    fun get(): Int

    /** Matches `AtomicInteger.incrementAndGet()` — returns the value *after* incrementing. */
    fun incrementAndGet(): Int

    /** Matches `AtomicInteger.decrementAndGet()` — returns the value *after* decrementing. */
    fun decrementAndGet(): Int

    /** Matches `AtomicInteger.updateAndGet(...)` — returns the value *after* applying [update]. */
    fun updateAndGet(update: (Int) -> Int): Int

    fun set(value: Int)
}
