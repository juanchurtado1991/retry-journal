package com.retryjournal.sample.ui.components.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retryjournal.sample.ui.theme.AppDimens

@Composable
internal fun DemoActionBar(
    uploadEnabled: Boolean,
    sendEnabled: Boolean,
    syncEnabled: Boolean,
    onUploadClick: () -> Unit,
    onSendClick: () -> Unit,
    onSyncClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
            UploadButton(enabled = uploadEnabled, onClick = onUploadClick)
            SendMutationsButton(enabled = sendEnabled, onClick = onSendClick)
        }
        SyncButton(enabled = syncEnabled, onClick = onSyncClick)
    }
}
