package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException

/**
 * Drains [queue], replaying each entry through [HttpReplayer] and applying the outcome: 2xx
 * delivers, a non-retry-worthy 4xx dead-letters, anything else stops the flush early and leaves
 * the entry queued for next time. All Ktor request-building mechanics live in [HttpReplayer] —
 * this class only orchestrates the loop and interprets outcomes.
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    private val httpReplayer = HttpReplayer()

    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult {
        httpReplayer.assertSafeToReplayWith(client)

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

                shouldDeadLetter(status) -> {
                    try {
                        deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                        queue.remove(entry.id)
                        deadLettered++
                        onProgress(FlushProgress.DeadLettered(entry.id))
                    } catch (_: Throwable) {
                        return FlushResult(delivered, deadLettered, stoppedEarly = true)
                    }
                }

                else -> return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }
        }
    }

    suspend fun getEntry(): QueueEntry? = queue.peek()

    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode =
        httpReplayer.send(client, entry)

    private suspend fun trySend(client: HttpClient, entry: QueueEntry): HttpStatusCode? = try {
        getStatus(client, entry)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: OfflineQueuedException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun shouldDeadLetter(status: HttpStatusCode): Boolean {
        val value = status.value
        return value in CLIENT_ERROR_STATUS_LOWER_BOUND..CLIENT_ERROR_STATUS_UPPER_BOUND &&
            value !in RETRY_WORTHY_CLIENT_ERROR_STATUSES
    }
}
