package com.retryjournal

import java.util.concurrent.atomic.AtomicInteger

actual class TestCounter actual constructor(initial: Int) {
    private val atomic = AtomicInteger(initial)
    actual fun get(): Int = atomic.get()
    actual fun incrementAndGet(): Int = atomic.incrementAndGet()
    actual fun decrementAndGet(): Int = atomic.decrementAndGet()

    actual fun updateAndGet(update: (Int) -> Int): Int {
        while (true) {
            val current = atomic.get()
            val next = update(current)
            if (atomic.compareAndSet(current, next)) return next
        }
    }

    actual fun set(value: Int) = atomic.set(value)
}
