package com.retryjournal.sample.ui.components.actions

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.retryjournal.sample.app.AppConstants
import com.retryjournal.sample.app.AppStrings

@Composable
internal fun SendMutationsButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(enabled = enabled, onClick = onClick) {
        Text(AppStrings.SEND_BUTTON_PREFIX + AppConstants.SIMPLE_SEND_COUNT + AppStrings.SEND_BUTTON_SUFFIX)
    }
}
