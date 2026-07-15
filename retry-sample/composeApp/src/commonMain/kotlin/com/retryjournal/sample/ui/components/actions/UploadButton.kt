package com.retryjournal.sample.ui.components.actions

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.retryjournal.sample.app.AppStrings

@Composable
internal fun UploadButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(enabled = enabled, onClick = onClick) {
        Text(AppStrings.UPLOAD_BUTTON)
    }
}
