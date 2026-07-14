package com.ghostserializer.sync.queue

import okio.BufferedSink
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Owns the two OS-level handles [com.ghostserializer.sync.queue.disk.DiskQueue] keeps open across calls instead of reopening one per
 * operation: an appending sink for writes, a read-only handle for reads. Both are opened lazily on
 * first use. Closing is defensive — if the underlying `close()` throws, the cached reference is
 * still cleared in a `finally`, so a later reopen isn't blocked by a stale, already-closed handle.
 */
internal class RecordFileHandles(
    private val fileSystem: FileSystem,
    private val path: Path,
) {
    private var appendSink: BufferedSink? = null
    private var readHandle: FileHandle? = null

    fun appendSink(): BufferedSink = appendSink
        ?: fileSystem.appendingSink(path, mustExist = false)
            .buffer()
            .also { appendSink = it }

    fun readHandle(): FileHandle = readHandle
        ?: fileSystem
            .openReadOnly(path)
            .also { readHandle = it }

    fun closeAppendSink() = try {
        appendSink?.close()
    } finally {
        appendSink = null
    }

    fun closeReadHandle() = try {
        readHandle?.close()
    } finally {
        readHandle = null
    }

    /** Closes both, guaranteeing the read handle
     *  is still released even if closing the append
     * sink throws. */
    fun closeAll() {
        try {
            closeAppendSink()
        } finally {
            closeReadHandle()
        }
    }
}
