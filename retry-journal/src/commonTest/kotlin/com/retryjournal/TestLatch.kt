package com.retryjournal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/** A single-shot, cross-thread signal — the multiplatform equivalent of `CountDownLatch(1)`. */
class TestLatch {
    private val deferred = CompletableDeferred<Unit>()

    fun countDown() {
        deferred.complete(Unit)
    }

    /** Blocks the current (real OS) thread until [countDown] is called. */
    fun await() = runBlocking { deferred.await() }
}
