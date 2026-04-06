package com.nexio.tv.data.repository.benchmark

/**
 * A single frontier advancement event: the contiguous playable byte frontier
 * moved forward at time [tMs] by [deltaContiguousBytes] bytes.
 */
data class FrontierEvent(
    val tMs: Long,
    val contiguousFrontierBytes: Long,
    val deltaContiguousBytes: Long
)

/**
 * Summary metrics derived from the frontier event timeline.
 */
data class FrontierMetrics(
    val totalFrontierEvents: Int,
    val finalFrontierBytes: Long,
    val frontierAdvanceRateMbps: Double?,
    val frontierStallCount: Int,
    val maxFrontierStallMs: Long
)

/**
 * Result of the shadow-player buffer-survival simulation.
 */
data class ShadowPlayerSimulationResult(
    val maxSustainableBitrateMbps: Double,
    val safeBudgetMbps: Double,
    val searchIterations: Int,
    val startupBufferMs: Long,
    val maxBufferMs: Long,
    val simulatedRebufferCount: Int
)
