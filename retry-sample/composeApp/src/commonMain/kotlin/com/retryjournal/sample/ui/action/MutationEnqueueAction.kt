package com.retryjournal.sample.ui.action

import com.retryjournal.client.OfflineQueuedException
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.shared.MutationRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Returns diagnostic info about exceptions thrown by each request. */
internal suspend fun enqueueSampleMutations(count: Int): String {
    val errors = mutableListOf<String>()
    val mutex = Mutex()
    val concurrencyLimit = Semaphore(AppConstants.ENQUEUE_CONCURRENCY)
    coroutineScope {
        repeat(count) { index ->
            launch {
                concurrencyLimit.withPermit {
                    val request = MutationRequest(
                        id = AppStrings.MUTATION_ID_PREFIX + index,
                        payload = AppStrings.MUTATION_PAYLOAD_PREFIX + index,
                        createdAtMillis = index.toLong(),
                    )
                    val result = runCatching { SyncSetup.mutationApi.createMutation(request) }
                    result.onFailure { e ->
                        val chain = buildString {
                            var current: Throwable? = e
                            while (current != null) {
                                if (isNotEmpty()) append("<-")
                                append(current::class.simpleName).append(": ").append(current.message)
                                current = current.cause
                            }
                        }
                        mutex.withLock { if (errors.size < 2) errors.add(chain) }
                    }
                }
            }
        }
    }
    return errors.firstOrNull() ?: "all ok"
}

