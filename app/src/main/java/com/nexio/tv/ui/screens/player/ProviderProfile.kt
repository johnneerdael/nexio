package com.nexio.tv.ui.screens.player

internal data class ProviderProfile(
    val chunkBytes: Long = 8L * 1024L * 1024L,
    val normalFragmentBytes: Long = 8L * 1024L * 1024L,
    val fillHorizonBytes: Long = 256L * 1024L * 1024L,
    val lowWaterBytes: Long = fillHorizonBytes / 2,
    val retainBehindBytes: Long = 8L * 1024L * 1024L,
    val maxConnections: Int = 1
) {
    init {
        require(chunkBytes > 0L)
        require(normalFragmentBytes > 0L)
        require(fillHorizonBytes > 0L)
        require(lowWaterBytes >= 0L)
        require(retainBehindBytes >= 0L)
        require(lowWaterBytes <= fillHorizonBytes)
        require(retainBehindBytes <= fillHorizonBytes)
        require(maxConnections == 1)
    }

    companion object {
        fun forMemoryBudget(memoryBudget: MemoryBudget): ProviderProfile {
            return ProviderProfile(
                fillHorizonBytes = memoryBudget.effectiveFillHorizonBytes,
                lowWaterBytes = memoryBudget.effectiveFillHorizonBytes / 2L
            )
        }
    }
}
