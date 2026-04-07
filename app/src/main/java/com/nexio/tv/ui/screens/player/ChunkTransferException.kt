package com.nexio.tv.ui.screens.player

import java.io.IOException

/**
 * Typed errors for chunk transport failures surfaced across the parallel-range
 * transport stack. Kept `internal` so they remain invisible to Media3 callers
 * (which only see `IOException`), but shared between
 * [SharedParallelTransportManager], [ParallelRangeDataSource], and
 * [SequentialReadCursor] so the boundary doesn't force the manager to fabricate
 * a type owned by its caller.
 */
internal open class ChunkTransferException(
    val chunkIndex: Long,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal class ChunkWaitTimeoutException(
    chunkIndex: Long,
    timeoutMs: Long,
    cause: Throwable? = null
) : ChunkTransferException(
    chunkIndex = chunkIndex,
    message = "Timed out waiting ${timeoutMs}ms for chunk $chunkIndex",
    cause = cause
)

internal class ChunkDownloadException(
    chunkIndex: Long,
    message: String,
    cause: Throwable? = null
) : ChunkTransferException(
    chunkIndex = chunkIndex,
    message = message,
    cause = cause
)
