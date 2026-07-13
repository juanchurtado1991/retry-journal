package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.FrozenHttpRequestMeta

/**
 * A request that was rejected by the server (4xx) and parked for manual inspection or retry.
 *
 * `equals()`/`hashCode()`/`toString()` are handwritten for the same reason as
 * [com.ghostserializer.sync.queue.QueueEntry]: the compiler-generated versions would
 * compare/hash [body] (a `ByteArray`) by reference and print it as `[B@...`.
 */
data class DeadLetterEntry(
    val id: DeadLetterEntryId,
    val meta: FrozenHttpRequestMeta,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is DeadLetterEntry) {
            return false
        }
        return id == other.id && meta == other.meta && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = HASH_MULTIPLIER * result + meta.hashCode()
        result = HASH_MULTIPLIER * result + body.contentHashCode()
        return result
    }

    override fun toString(): String =
        "${this::class.simpleName}(id=$id, meta=$meta, body.size=${body.size})"

    private companion object {
        const val HASH_MULTIPLIER: Int = 31
    }
}
