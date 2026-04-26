package com.nexio.tv.core.metadata.router

interface MetadataProviderAdapter {
    val provider: MetadataPrimaryProvider

    fun supports(step: ProviderPlanStep): Boolean

    suspend fun execute(
        route: MetadataRoute,
        step: ProviderPlanStep
    ): ProviderStepResult
}
