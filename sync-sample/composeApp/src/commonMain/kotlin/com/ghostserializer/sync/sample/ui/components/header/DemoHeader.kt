package com.ghostserializer.sync.sample.ui.components.header

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.ghostserializer.sync.sample.app.AppStrings
import com.ghostserializer.sync.sample.ui.theme.AppDimens

@Composable
internal fun DemoHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.TITLE_SUBTITLE_SPACING)) {
        Text(
            AppStrings.SCREEN_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            AppStrings.APP_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
