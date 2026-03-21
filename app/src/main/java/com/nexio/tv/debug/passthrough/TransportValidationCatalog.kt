package com.nexio.tv.debug.passthrough

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportValidationCatalog @Inject constructor(
    private val hostedAssetStore: TransportValidationHostedAssetStore,
) {
    suspend fun availableSamples(): List<TransportValidationSampleOption> = runCatching {
        hostedAssetStore.loadManifest().samples.map { sample ->
            TransportValidationSampleOption(
                id = sample.id,
                displayName = sample.displayName,
            )
        }
    }.getOrDefault(emptyList())
}

data class TransportValidationSampleOption(
    val id: String,
    val displayName: String,
)
