package com.retryjournal.worker

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkRequest
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** No `Context`/`WorkManager` involved — [buildRetryJournalConstraints], [buildRetryJournalInputData]
 * and [buildRetryJournalPeriodicWorkRequest] are pure request builders, so this runs as a plain JVM
 * unit test (no emulator/Robolectric needed). */
class RetryJournalWorkRequestFactoryTest {

    // --- buildRetryJournalConstraints ---

    @Test
    fun testConstraintsRequireNetworkByDefault() {
        val constraints = buildRetryJournalConstraints(RetryJournalSchedulerConfig(requiresNetwork = true))
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun testConstraintsAllowNoNetworkWhenDisabled() {
        val constraints = buildRetryJournalConstraints(RetryJournalSchedulerConfig(requiresNetwork = false))
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
    }

    // --- buildRetryJournalInputData ---

    @Test
    fun testInputDataCarriesMaxRetryAttempts() {
        val data = buildRetryJournalInputData(RetryJournalSchedulerConfig(maxRetryAttempts = 7))
        assertEquals(7, data.getInt(RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS, -1))
    }

    @Test
    fun testInputDataCarriesZeroMaxRetryAttempts() {
        val data = buildRetryJournalInputData(RetryJournalSchedulerConfig(maxRetryAttempts = 0))
        assertEquals(0, data.getInt(RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS, -1))
    }

    // --- buildRetryJournalPeriodicWorkRequest ---

    @Test
    fun testRequestUsesConfiguredIntervalWhenAboveWorkManagerMinimum() {
        val config = RetryJournalSchedulerConfig(intervalMs = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS + 60_000L)
        val request = buildRetryJournalPeriodicWorkRequest(config)
        assertEquals(config.intervalMs, request.workSpec.intervalDuration)
    }

    @Test
    fun testRequestClampsIntervalToWorkManagerMinimum() {
        // WorkManager silently floors periodic intervals below 15 minutes — assert that clamp is
        // real and not something RetryJournalSchedulerConfig itself needs to enforce.
        val config = RetryJournalSchedulerConfig(intervalMs = 1_000L)
        val request = buildRetryJournalPeriodicWorkRequest(config)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, request.workSpec.intervalDuration)
    }

    @Test
    fun testRequestUsesConfiguredBackoffWhenAboveWorkManagerMinimum() {
        val config = RetryJournalSchedulerConfig(retryDelayMs = WorkRequest.MIN_BACKOFF_MILLIS + 5_000L)
        val request = buildRetryJournalPeriodicWorkRequest(config)
        assertEquals(BackoffPolicy.LINEAR, request.workSpec.backoffPolicy)
        assertEquals(config.retryDelayMs, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun testRequestClampsBackoffToWorkManagerMinimum() {
        val config = RetryJournalSchedulerConfig(retryDelayMs = 1_000L)
        val request = buildRetryJournalPeriodicWorkRequest(config)
        assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun testRequestPropagatesNetworkConstraintAndRetryAttempts() {
        val config = RetryJournalSchedulerConfig(requiresNetwork = false, maxRetryAttempts = 3)
        val request = buildRetryJournalPeriodicWorkRequest(config)
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(3, request.workSpec.input.getInt(RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS, -1))
    }

    @Test
    fun testRequestIsPeriodic() {
        val request = buildRetryJournalPeriodicWorkRequest(RetryJournalSchedulerConfig())
        assertTrue(request.workSpec.isPeriodic)
    }
}
