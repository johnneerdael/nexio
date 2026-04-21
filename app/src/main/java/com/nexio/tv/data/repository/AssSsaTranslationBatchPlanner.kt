package com.nexio.tv.data.repository

internal data class AssSsaTranslationBatchConfig(
    val maxEvents: Int = 80,
    val maxVisibleChars: Int = 8_000,
    val rampUpEnabled: Boolean = true
)

internal data class AssSsaTranslationBatch(
    val units: List<AssSsaProtectedTranslationUnit>,
    val leadOverlap: Int,
    val coreCount: Int,
    val trailOverlap: Int
) {
    val coreUnits: List<AssSsaProtectedTranslationUnit>
        get() = units.subList(leadOverlap, leadOverlap + coreCount)
}

internal object AssSsaTranslationBatchPlanner {
    fun plan(
        units: List<AssSsaProtectedTranslationUnit>,
        config: AssSsaTranslationBatchConfig = AssSsaTranslationBatchConfig()
    ): List<AssSsaTranslationBatch> {
        val translatable = units.filter {
            it.risk != AssSsaRisk.PreserveOnly && it.protectedText.isNotBlank()
        }
        if (translatable.isEmpty()) return emptyList()

        return planRampedTranslationBatches(
            items = translatable,
            maxCoreEntries = config.maxEvents,
            maxCoreChars = config.maxVisibleChars,
            sizeOf = { unit -> unit.protectedText.length },
            rampUpEnabled = config.rampUpEnabled
        ).map { ramped ->
            AssSsaTranslationBatch(
                units = ramped.items,
                leadOverlap = ramped.leadOverlap,
                coreCount = ramped.coreCount,
                trailOverlap = ramped.trailOverlap
            )
        }
    }
}
