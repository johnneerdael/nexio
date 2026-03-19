package com.nexio.tv.debug.passthrough

import android.content.res.AssetManager
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class TransportValidationManifestLoader @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = false }

    fun loadFromAssets(
        assetManager: AssetManager,
        assetPath: String = MANIFEST_ASSET_PATH,
    ): TransportValidationManifest = assetManager.open(assetPath).use(::loadFromStream)

    fun loadFromStream(
        inputStream: InputStream
    ): TransportValidationManifest = parseManifest(inputStream.bufferedReader().use { it.readText() })

    fun parseManifest(
        manifestJson: String
    ): TransportValidationManifest = json.decodeFromString<TransportValidationManifest>(manifestJson)
        .also(::validateManifest)

    private fun validateManifest(
        manifest: TransportValidationManifest
    ) {
        require(manifest.version.isNotBlank()) { "Transport validation manifest version is required" }
        require(manifest.samples.isNotEmpty()) { "Transport validation manifest must contain samples" }

        val seenIds = mutableSetOf<String>()
        manifest.samples.forEach { sample ->
            require(sample.id.isNotBlank()) { "Sample id is required" }
            require(seenIds.add(sample.id)) { "Duplicate sample id '${sample.id}'" }
            require(sample.sourceAssetPath.isNotBlank()) { "Sample '${sample.id}' sourceAssetPath is required" }
            require(sample.referenceAssetPath.isNotBlank()) {
                "Sample '${sample.id}' referenceAssetPath is required"
            }
            require(sample.assetChecksums["sourceAssetPath"]?.isNotBlank() == true) {
                "Sample '${sample.id}' must declare a sourceAssetPath checksum"
            }
            if (sample.elementaryAssetPath != null) {
                require(sample.assetChecksums["elementaryAssetPath"]?.isNotBlank() == true) {
                    "Sample '${sample.id}' must declare an elementaryAssetPath checksum"
                }
            }
            require(sample.assetChecksums["referenceAssetPath"]?.isNotBlank() == true) {
                "Sample '${sample.id}' must declare a referenceAssetPath checksum"
            }
        }
    }

    companion object {
        const val MANIFEST_ASSET_PATH = "transport_validation_manifest.json"
    }
}
