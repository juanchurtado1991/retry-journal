package com.retryjournal.sample.ui.components.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < AppDimens.ACTION_BAR_COMPACT_BREAKPOINT) {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
                PrimaryActionButtons(uploadEnabled, sendEnabled, onUploadClick, onSendClick)
                SyncButton(enabled = syncEnabled, onClick = onSyncClick)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PrimaryActionButtons(uploadEnabled, sendEnabled, onUploadClick, onSendClick)
                SyncButton(enabled = syncEnabled, onClick = onSyncClick)
            }
        }
    }
}

@Composable
private fun PrimaryActionButtons(
    uploadEnabled: Boolean,
    sendEnabled: Boolean,
    onUploadClick: () -> Unit,
    onSendClick: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.BUTTON_SPACING)) {
        UploadButton(enabled = uploadEnabled, onClick = onUploadClick)
        SendMutationsButton(enabled = sendEnabled, onClick = onSendClick)
    }
}
