package com.retryjournal.sample.ui.components.actions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.retryjournal.sample.app.AppStrings
import com.retryjournal.sample.app.FilePicker

@Composable
internal fun FilePickerUnsupportedHint() {
    if (FilePicker.isSupported) {
        return
    }
    Text(
        AppStrings.FILE_PICKER_UNSUPPORTED_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
