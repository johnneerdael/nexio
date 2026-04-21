package com.nexio.tv.data.repository

internal val DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE: IntArray = intArrayOf(5, 10, 20, 40, 60)
internal val DEFAULT_TRANSLATION_RAMP_TERMINAL: Int =
    DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE.last()

internal data class RampedTranslationBatch<T>(
    val items: List<T>,
    val leadOverlap: Int,
    val coreCount: Int,
    val trailOverlap: Int
) {
    init {
        require(leadOverlap >= 0 && coreCount >= 0 && trailOverlap >= 0)
        require(items.size == leadOverlap + coreCount + trailOverlap) {
            "items.size (${items.size}) must equal leadOverlap+coreCount+trailOverlap " +
                "(${leadOverlap + coreCount + trailOverlap})"
        }
    }

    val coreItems: List<T>
        get() = items.subList(leadOverlap, leadOverlap + coreCount)

    val coreIndices: IntRange
        get() = leadOverlap until (leadOverlap + coreCount)
}

internal fun <T> planRampedTranslationBatches(
    items: List<T>,
    maxCoreEntries: Int,
    maxCoreChars: Int,
    sizeOf: (T) -> Int,
    rampUpEnabled: Boolean = true,
    rampUpSchedule: IntArray = DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE
): List<RampedTranslationBatch<T>> {
    if (items.isEmpty()) return emptyList()

    val effectiveSchedule = if (rampUpEnabled && rampUpSchedule.isNotEmpty()) {
        rampUpSchedule.map { step -> step.coerceAtMost(maxCoreEntries).coerceAtLeast(1) }.toIntArray()
    } else {
        intArrayOf(maxCoreEntries.coerceAtLeast(1))
    }

    val coreBatches = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    var currentChars = 0

    fun limitForBatchIndex(index: Int): Int {
        val clamped = index.coerceAtMost(effectiveSchedule.lastIndex)
        return effectiveSchedule[clamped]
    }

    for (item in items) {
        val size = sizeOf(item)
        val nextChars = currentChars + size
        val limit = limitForBatchIndex(coreBatches.size)
        if (current.isNotEmpty() && (current.size >= limit || nextChars > maxCoreChars)) {
            coreBatches += current.toList()
            current = mutableListOf()
            currentChars = 0
        }
        current += item
        currentChars += size
    }
    if (current.isNotEmpty()) {
        coreBatches += current.toList()
    }

    if (coreBatches.size == 1) {
        val only = coreBatches.first()
        return listOf(
            RampedTranslationBatch(
                items = only,
                leadOverlap = 0,
                coreCount = only.size,
                trailOverlap = 0
            )
        )
    }

    return coreBatches.mapIndexed { index, coreBatch ->
        val overlapSize = overlapSizeForCore(coreBatch.size)
        val lead = if (index > 0) coreBatches[index - 1].takeLast(overlapSize) else emptyList()
        val trail = if (index < coreBatches.lastIndex) coreBatches[index + 1].take(overlapSize) else emptyList()
        RampedTranslationBatch(
            items = lead + coreBatch + trail,
            leadOverlap = lead.size,
            coreCount = coreBatch.size,
            trailOverlap = trail.size
        )
    }
}

private fun overlapSizeForCore(coreSize: Int): Int = when {
    coreSize >= 50 -> 4
    coreSize >= 30 -> 3
    coreSize >= 8 -> 2
    else -> 1
}
