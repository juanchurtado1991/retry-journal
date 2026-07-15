package com.retryjournal.sample.app

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal actual object FilePicker {
    actual val isSupported: Boolean = true

    actual suspend fun pickFile(): PickedFile? {
        val activity = RetryJournalSampleApplication.currentActivity as? MainActivity
            ?: return null
        return suspendCancellableCoroutine { continuation ->
            MainActivity.pickFile(activity) { file ->
                continuation.resume(file)
            }
        }
    }
}
