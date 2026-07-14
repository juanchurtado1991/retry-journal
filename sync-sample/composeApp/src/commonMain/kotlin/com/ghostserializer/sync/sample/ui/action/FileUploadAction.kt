package com.ghostserializer.sync.sample.ui.action

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.app.PickedFile
import com.ghostserializer.sync.sample.app.SyncSetup
import com.ghostserializer.sync.sample.ui.model.LogKind
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
