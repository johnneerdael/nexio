package com.nexio.tv.debug.passthrough

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportValidationCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestLoader: TransportValidationManifestLoader,
) {
    fun availableSamples(): List<TransportValidationSampleOption> = runCatching {
        manifestLoader.loadFromAssets(context.assets).samples.map { sample ->
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
