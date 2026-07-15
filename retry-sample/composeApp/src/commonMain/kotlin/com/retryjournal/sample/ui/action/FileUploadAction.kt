package com.retryjournal.sample.ui.action

import com.retryjournal.client.OfflineQueuedException
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.PickedFile
import com.retryjournal.sample.app.SyncSetup
import com.retryjournal.sample.ui.model.LogKind
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

internal suspend fun uploadPickedFile(file: PickedFile): Pair<String, LogKind> = try {
    SyncSetup.mutationApi.uploadFile(
        formData {
            append(
                AppStrings.UPLOAD_FORM_FIELD_NAME,
                file.bytes,
                Headers.build {
                    append(
                        HttpHeaders.ContentDisposition,
                        AppStrings.UPLOAD_CONTENT_DISPOSITION_PREFIX + file.name +
                            AppStrings.UPLOAD_CONTENT_DISPOSITION_SUFFIX,
                    )
                },
            )
        },
    )
    (AppStrings.LOG_UPLOAD_DELIVERED_PREFIX + file.name + AppStrings.LOG_UPLOAD_DELIVERED_SUFFIX) to
        LogKind.Success
} catch (_: OfflineQueuedException) {
    (AppStrings.LOG_UPLOAD_QUEUED_PREFIX + file.name + AppStrings.LOG_UPLOAD_QUEUED_SUFFIX) to
        LogKind.Info
}
