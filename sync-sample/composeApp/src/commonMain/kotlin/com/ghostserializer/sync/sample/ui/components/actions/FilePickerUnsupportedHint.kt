package com.ghostserializer.sync.sample.ui.components.actions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.app.FilePicker

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
