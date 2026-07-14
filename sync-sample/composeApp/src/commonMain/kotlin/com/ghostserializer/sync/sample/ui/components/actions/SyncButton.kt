package com.ghostserializer.sync.sample.ui.components.actions

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ghostserializer.sync.sample.app.AppStrings

@Composable
internal fun SyncButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(enabled = enabled, onClick = onClick) {
        Text(AppStrings.SYNC_BUTTON)
    }
}
