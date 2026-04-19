package com.nexio.tv.data.repository

internal data class AssSsaTranslationBatchConfig(
    val maxEvents: Int = 80,
    val maxVisibleChars: Int = 8_000
)

internal data class AssSsaTranslationBatch(
    val units: List<AssSsaProtectedTranslationUnit>
)

internal object AssSsaTranslationBatchPlanner {
    fun plan(
        units: List<AssSsaProtectedTranslationUnit>,
        config: AssSsaTranslationBatchConfig = AssSsaTranslationBatchConfig()
    ): List<AssSsaTranslationBatch> {
        val batches = mutableListOf<AssSsaTranslationBatch>()
        var current = mutableListOf<AssSsaProtectedTranslationUnit>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                batches += AssSsaTranslationBatch(current.toList())
                current = mutableListOf()
                chars = 0
            }
        }

        units.filter { it.risk != AssSsaRisk.PreserveOnly && it.protectedText.isNotBlank() }
            .forEach { unit ->
                val nextChars = chars + unit.protectedText.length
                if (current.isNotEmpty() &&
                    (current.size >= config.maxEvents || nextChars > config.maxVisibleChars)
                ) {
                    flush()
                }
                current += unit
                chars += unit.protectedText.length
            }
        flush()
        return batches
    }
}
