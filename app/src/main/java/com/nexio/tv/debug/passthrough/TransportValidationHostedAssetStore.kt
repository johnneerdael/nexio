package com.nexio.tv.debug.passthrough

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class TransportValidationHostedAssetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fetcher: TransportValidationAssetFetcher,
    private val manifestLoader: TransportValidationManifestLoader,
) {
    suspend fun loadManifest(): TransportValidationManifest = withContext(Dispatchers.IO) {
        fetchManifest()
    }

    suspend fun prepareSample(sampleId: String): PreparedTransportValidationSample =
        withContext(Dispatchers.IO) {
            val manifest = fetchManifest()
            val sample = manifest.samples.firstOrNull { it.id == sampleId } ?: error(
                "Transport validation sample not found sampleId=$sampleId"
            )
            val sourceAsset = ensureCachedAsset(sample, "sourceAssetPath", sample.sourceAssetPath)
            val referenceAsset =
                ensureCachedAsset(sample, "referenceAssetPath", sample.referenceAssetPath)
            val elementaryAsset =
                sample.elementaryAssetPath?.let { path ->
                    ensureCachedAsset(sample, "elementaryAssetPath", path)
                }
            PreparedTransportValidationSample(
                manifest = manifest,
                sample = sample,
                sourceFile = sourceAsset.file,
                referenceFile = referenceAsset.file,
                elementaryFile = elementaryAsset?.file,
                assetSource = TransportValidationAssetSourceSnapshot(
                    baseUrl = BASE_URL,
                    manifestUrl = manifestUrl(),
                    manifestVersion = manifest.version,
                    source = sourceAsset.toSnapshot(),
                    reference = referenceAsset.toSnapshot(),
                    elementary = elementaryAsset?.toSnapshot(),
                ),
            )
    }

    private fun fetchManifest(): TransportValidationManifest {
        val manifestJson = fetcher.fetchString(manifestUrl())
        return manifestLoader.parseManifest(manifestJson)
    }

    private fun ensureCachedAsset(
        sample: TransportValidationSample,
        checksumKey: String,
        remotePath: String,
    ): CachedTransportValidationAsset {
        val expectedChecksum = sample.assetChecksums[checksumKey]
            ?: error("Missing checksum '$checksumKey' for sample '${sample.id}'")
        val targetFile = File(cacheDirectory(), remotePath.substringAfterLast('/'))
        if (targetFile.exists()) {
            val localChecksum = sha256Hex(targetFile)
            if (localChecksum.equals(expectedChecksum, ignoreCase = true)) {
                logInfo("Reusing cached validator asset path=$remotePath file=${targetFile.name}")
                return CachedTransportValidationAsset(
                    file = targetFile,
                    remotePath = remotePath,
                    checksum = localChecksum,
                    cacheState = TransportValidationAssetCacheState.REUSED,
                )
            }
            targetFile.delete()
        }
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        fetcher.fetchToFile(assetUrl(remotePath), tempFile)
        val downloadedChecksum = sha256Hex(tempFile)
        require(downloadedChecksum.equals(expectedChecksum, ignoreCase = true)) {
            "Downloaded validator asset checksum mismatch path=$remotePath"
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.renameTo(targetFile)
        logInfo("Downloaded validator asset path=$remotePath file=${targetFile.name}")
        return CachedTransportValidationAsset(
            file = targetFile,
            remotePath = remotePath,
            checksum = downloadedChecksum,
            cacheState = TransportValidationAssetCacheState.DOWNLOADED,
        )
    }

    private fun cacheDirectory(): File = File(context.filesDir, CACHE_DIRECTORY_NAME).apply { mkdirs() }

    private fun manifestUrl(): String = "$BASE_URL/${TransportValidationManifestLoader.MANIFEST_ASSET_PATH}"

    private fun assetUrl(path: String): String = "$BASE_URL/$path"

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val BASE_URL = "https://files.thepi.es/validator"
        private const val CACHE_DIRECTORY_NAME = "transport-validation-assets"
        private const val TAG = "TransportValidation"
    }
}

interface TransportValidationAssetFetcher {
    fun fetchString(url: String): String

    fun fetchToFile(url: String, targetFile: File)
}

data class PreparedTransportValidationSample(
    val manifest: TransportValidationManifest,
    val sample: TransportValidationSample,
    val sourceFile: File,
    val referenceFile: File,
    val elementaryFile: File? = null,
    val assetSource: TransportValidationAssetSourceSnapshot,
)

private data class CachedTransportValidationAsset(
    val file: File,
    val remotePath: String,
    val checksum: String,
    val cacheState: TransportValidationAssetCacheState,
) {
    fun toSnapshot(): TransportValidationCachedAssetSnapshot =
        TransportValidationCachedAssetSnapshot(
            remotePath = remotePath,
            localFileName = file.name,
            localFilePath = file.absolutePath,
            checksum = checksum,
            cacheState = cacheState,
        )
}
