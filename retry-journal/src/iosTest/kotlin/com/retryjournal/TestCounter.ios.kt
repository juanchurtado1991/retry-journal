package com.retryjournal

import kotlin.concurrent.AtomicInt

actual class TestCounter actual constructor(initial: Int) {
    private val atomic = AtomicInt(initial)
    actual fun get(): Int = atomic.value
    actual fun incrementAndGet(): Int = atomic.addAndGet(1)
    actual fun decrementAndGet(): Int = atomic.addAndGet(-1)

    actual fun updateAndGet(update: (Int) -> Int): Int {
        while (true) {
            val current = atomic.value
            val next = update(current)
            if (atomic.compareAndSet(current, next)) return next
        }
    }

    actual fun set(value: Int) {
        atomic.value = value
    }
}
