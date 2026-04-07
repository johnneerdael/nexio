package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec

/**
 * Immutable per-open state for [ParallelRangeDataSource]. Captures the values resolved
 * during a single `open()` call so that reads and metadata queries do not have to reach
 * into mutable fields.
 */
@UnstableApi
internal data class OpenSession(
    val requestSpec: DataSpec,
    val resolvedUri: Uri?,
    val startPosition: Long,
    val openLength: Long,
    val totalFileLength: Long,
    val acceptsRanges: Boolean,
    val responseHeaders: Map<String, List<String>>
)
