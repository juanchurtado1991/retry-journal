package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException

/**
 * Reads [queue] and resumes traffic. Knows nothing about what triggers it — a `kmpworkmanager`
 * worker, `androidx.work`, a plain coroutine timer, or a server-side cron can all call [flush]
 * the same way. See CONVENTIONS.md for why this engine has no scheduler dependency at all.
 *
 * [flush]'s client must **not** have [com.ghostserializer.sync.client.GhostOfflineQueuePlugin]
 * installed: replaying an entry that fails again would otherwise be silently re-enqueued as a
 * *new*, duplicate record by that plugin while this loop still holds the original — install Ghost
 * content negotiation on it, nothing that intercepts the send pipeline.
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    /** [onProgress] fires once per entry, right after it's actually resolved — see [FlushProgress].
     * Defaulted to a no-op so existing callers don't need to change anything; a caller that wants
     * to show the queue draining in real time (rather than waiting for the final [FlushResult])
     * passes one. It's `suspend` so a UI can pace itself against real flush progress (e.g. a short
     * `delay` to make each step visible) instead of racing through the whole queue in milliseconds. */
    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult {
        var delivered = 0
        var deadLettered = 0

        while (true) {
            val entry = getEntry() ?: return FlushResult(delivered, deadLettered, stoppedEarly = false)
            val status = trySend(client, entry) ?: return FlushResult(delivered, deadLettered, stoppedEarly = true)

            when {
                status.isSuccess() -> {
                    queue.remove(entry.id)
                    delivered++
                    onProgress(FlushProgress.Delivered(entry.id))
                }

                isClientError(status) -> {
                    deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                    queue.remove(entry.id)
                    deadLettered++
                    onProgress(FlushProgress.DeadLettered(entry.id))
                }

                else -> return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }
        }
    }

    /** The oldest entry [flush] would process next — peeked, not consumed. Exposed so a caller
     * can drive its own step-by-step loop (peek one, decide, act, repeat) instead of only being
     * able to call [flush] end-to-end — e.g. a UI that wants to pace or narrate each item itself
     * rather than reacting to [FlushProgress] after the fact. [flush] itself is built on this,
     * not a separate `queue.peek()` call. */
    suspend fun getEntry(): QueueEntry? = queue.peek()

    /** Sends [entry] and returns the raw response status — the same three-way outcome [flush]
     * itself acts on (2xx / 4xx / anything else), but without [flush]'s side effects: nothing is
     * removed from [queue] or moved to the dead-letter queue. A caller using this directly (with
     * [getEntry]) owns deciding what those side effects should be and when to apply them. */
    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode = send(client, entry)

    /** [getStatus] wrapped for [flush]'s own use: a `null` result means the connection just isn't
     * there right now (a network failure, or the request was captured and queued instead of sent)
     * — [flush] treats that the same as a 5xx, stopping without touching this entry. */
    private suspend fun trySend(client: HttpClient, entry: QueueEntry): HttpStatusCode? = try {
        getStatus(client, entry)
    } catch (_: IOException) {
        null
    } catch (_: OfflineQueuedException) {
        null
    }

    /**
     * One pass over [QueueEntry.meta]'s headers instead of two: the old version did an exact-case
     * map lookup for `Content-Type` (its own hash + equals) and *then* a full loop re-comparing
     * every header's name against `Content-Type`/`Content-Length` again to skip re-adding them.
     * This finds the Content-Type value and filters body-derived headers in the same iteration —
     * same asymptotic cost, but one fewer hash/equals pass over the map, and it no longer requires
     * `Content-Type` to appear in exactly that casing to be found (the loop's comparison was
     * already case-insensitive; the old map lookup wasn't, so a caller-set `content-type` header
     * would silently miss the fast lookup while still — correctly — getting filtered from the
     * loop, losing the content type on replay).
     */
    private suspend fun send(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        val meta = entry.meta
        val headers = meta.headers
        val response: HttpResponse = client.request(meta.url) {
            method = HttpMethod.parse(meta.method)

            var contentType: String? = null
            for ((name, value) in headers) {
                if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                    contentType = value
                } else if (!name.equals(HttpHeaders.ContentLength, ignoreCase = true)) {
                    header(name, value)
                }
            }

            setBody(
                if (contentType != null) {
                    ByteArrayContent(entry.body, ContentType.parse(contentType))
                } else {
                    entry.body
                },
            )
        }
        return response.status
    }

    private fun isClientError(status: HttpStatusCode): Boolean {
        return status.value in CLIENT_ERROR_STATUS_LOWER_BOUND..CLIENT_ERROR_STATUS_UPPER_BOUND
    }
}
