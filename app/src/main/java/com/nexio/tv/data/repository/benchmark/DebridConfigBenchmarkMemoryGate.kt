package com.nexio.tv.data.repository.benchmark

import androidx.media3.common.util.UnstableApi
import com.nexio.tv.ui.screens.settings.MemoryBudget
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProfileRunDecision {
    data object Run : ProfileRunDecision
    data class Unsupported(val reason: String) : ProfileRunDecision
}

@UnstableApi
@Singleton
class DebridConfigBenchmarkMemoryGate internal constructor(
    private val benchmarkBudgetMb: Int,
    private val reservedBufferMb: Int
) {
    @Inject
    constructor() : this(
        benchmarkBudgetMb = MemoryBudget.budgetMb,
        reservedBufferMb = MemoryBudget.defaultBufferSizeMb
    )

    fun evaluate(
        parallelConnectionCount: Int,
        chunkSizeMb: Int
    ): ProfileRunDecision {
        val estimatedUsageMb = MemoryBudget.totalUsageMb(
            bufferMb = reservedBufferMb,
            connectionCount = parallelConnectionCount,
            chunkSizeMb = chunkSizeMb,
            parallelEnabled = true
        )
        return if (estimatedUsageMb <= benchmarkBudgetMb) {
            ProfileRunDecision.Run
        } else {
            ProfileRunDecision.Unsupported(EXCEEDS_SAFE_MEMORY_BUDGET_REASON)
        }
    }

    companion object {
        const val EXCEEDS_SAFE_MEMORY_BUDGET_REASON = "Exceeds safe memory budget"
    }
}
