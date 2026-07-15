package com.retryjournal.queue

/**
 * A live, decoded record read back from a [com.retryjournal.queue.disk.DiskQueue].
 *
 * `equals()`/`hashCode()`/`toString()` are handwritten, not the compiler-generated data class
 * versions: the default ones compare/hash [body] by reference (it's a `ByteArray`), so two
 * entries with byte-for-byte identical bodies would compare unequal, and the default `toString()`
 * would print the array's identity (`[B@...`) instead of anything useful.
 */
data class QueueEntry(
    val id: QueueEntryId,
    val meta: FrozenHttpRequestMeta,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is QueueEntry) {
            return false
        }

        return id == other.id &&
                meta == other.meta &&
                body.contentEquals(other.body)
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
