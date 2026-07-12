package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
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
 * [flush]'s [client] must **not** have [com.ghostserializer.sync.client.GhostOfflineQueuePlugin]
 * installed: replaying an entry that fails again would otherwise be silently re-enqueued as a
 * *new*, duplicate record by that plugin while this loop still holds the original — install Ghost
 * content negotiation on it, nothing that intercepts the send pipeline.
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    suspend fun flush(client: HttpClient): FlushResult {
        var delivered = 0
        var deadLettered = 0

        while (true) {
            val entry = queue.peek() ?: return FlushResult(delivered, deadLettered, stoppedEarly = false)

            val status = try {
                send(client, entry)
            } catch (cause: IOException) {
                return FlushResult(delivered, deadLettered, stoppedEarly = true)
            } catch (cause: OfflineQueuedException) {
                return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }

            when {
                status.isSuccess() -> {
                    queue.remove(entry.id)
                    delivered++
                }

                isClientError(status) -> {
                    deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                    queue.remove(entry.id)
                    deadLettered++
                }

                else -> {
                    return FlushResult(delivered, deadLettered, stoppedEarly = true)
                }
            }
        }
    }

    private suspend fun send(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        val response: HttpResponse = client.request(entry.meta.url) {
            method = HttpMethod.parse(entry.meta.method)

            val contentType = entry.meta.headers[HttpHeaders.ContentType]
            if (contentType != null) {
                setBody(ByteArrayContent(entry.body, ContentType.parse(contentType)))
            } else {
                setBody(entry.body)
            }

            for ((name, value) in entry.meta.headers) {
                if (!isBodyDerivedHeader(name)) {
                    header(name, value)
                }
            }
        }
        return response.status
    }

    private fun isBodyDerivedHeader(name: String): Boolean {
        return name.equals(HttpHeaders.ContentType, ignoreCase = true) ||
            name.equals(HttpHeaders.ContentLength, ignoreCase = true)
    }

    private fun isClientError(status: HttpStatusCode): Boolean {
        return status.value in SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND..SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
    }
}
