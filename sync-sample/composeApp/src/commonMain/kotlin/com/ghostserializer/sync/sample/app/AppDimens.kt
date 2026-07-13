package com.ghostserializer.sync.sample.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object AppDimens {
    val SCREEN_PADDING: Dp = 24.dp
    val SECTION_SPACING: Dp = 16.dp
    val BUTTON_SPACING: Dp = 8.dp

    val CARD_PADDING: Dp = 16.dp
    val CARD_INTERNAL_SPACING: Dp = 8.dp
    val STAT_CARD_SPACING: Dp = 12.dp
    val ACTIVITY_LOG_HEIGHT: Dp = 220.dp
    val STATUS_DOT_SIZE: Dp = 10.dp
    val TITLE_SUBTITLE_SPACING: Dp = 4.dp
    val LOG_ROW_VERTICAL_PADDING: Dp = 2.dp

    val QUEUE_CHIP_SIZE: Dp = 28.dp
    val QUEUE_CHIP_SPACING: Dp = 6.dp

    /** Compose Desktop's own default (800x600) is shorter than this screen's content — the
     * activity log and advanced section would be clipped off below the fold instead of just
     * needing a scroll. Sized to comfortably fit the primary flow without scrolling on a typical
     * desktop display. */
    val DESKTOP_WINDOW_WIDTH: Dp = 900.dp
    val DESKTOP_WINDOW_HEIGHT: Dp = 800.dp
}
