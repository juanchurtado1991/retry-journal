package com.retryjournal.queue.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentTimeMillis(): Long = memScoped {
    val timeValue = alloc<timeval>()
    gettimeofday(timeValue.ptr, null)
    (timeValue.tv_sec * MILLIS_PER_SECOND) + (timeValue.tv_usec / MICROS_PER_MILLI)
}

private const val MILLIS_PER_SECOND = 1000L
private const val MICROS_PER_MILLI = 1000L
