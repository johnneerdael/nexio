package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.WatchProgress
import kotlin.math.roundToInt

internal fun formatContinueWatchingProgressLabel(
    progress: WatchProgress,
    resumeLabel: String
): String {
    val percentWatched = (progress.progressPercentage * 100f)
        .roundToInt()
        .coerceIn(0, 100)

    return if (percentWatched > 0) {
        "$percentWatched% watched"
    } else {
        resumeLabel
    }
}
