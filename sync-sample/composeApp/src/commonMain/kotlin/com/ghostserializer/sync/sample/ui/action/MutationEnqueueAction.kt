package com.ghostserializer.sync.sample.ui.action

import com.ghostserializer.sync.sample.app.AppConstants
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.app.SyncSetup
import com.ghostserializer.sync.sample.shared.MutationRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun enqueueSampleMutations(count: Int) {
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
                    runCatching { SyncSetup.mutationApi.createMutation(request) }
                }
            }
        }
    }
}
