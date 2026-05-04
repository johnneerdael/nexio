package com.nexio.tv.core.artwork

import java.io.File

class ArtworkAssetDiskCache(
    private val cacheRoot: File
) {
    fun recordFor(
        assetKey: ArtworkAssetKey,
        decision: ArtworkDecision,
        provider: ArtworkProviderId?,
        sourceHash: String,
        mimeType: String?,
        byteCount: Long,
        fetchedAtMs: Long
    ): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = assetKey,
            decisionKey = decision.decisionKey,
            provider = provider,
            imageType = decision.imageType,
            imageLanguage = "en",
            relativePath = relativePathFor(assetKey),
            mimeType = mimeType,
            byteCount = byteCount,
            sourceHash = sourceHash,
            policyVersion = decision.policyVersion,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = decision.expiresAtMs,
            staleUntilMs = decision.staleUntilMs ?: decision.expiresAtMs
        )

    fun write(record: ArtworkAssetRecord, bytes: ByteArray): File {
        val file = File(cacheRoot, record.relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    fun getExistingFile(assetKey: ArtworkAssetKey): File? {
        val file = File(cacheRoot, relativePathFor(assetKey))
        return file.takeIf { it.isFile }
    }

    fun relativePathFor(assetKey: ArtworkAssetKey): String {
        val parts = assetKey.value.split(":")
        val provider = parts.getOrNull(1)?.safePathSegment() ?: "unknown"
        val imageType = parts.getOrNull(2)?.safePathSegment() ?: "unknown"
        return listOf(
            "artwork-assets",
            provider,
            imageType,
            "${assetKey.value.safePathSegment()}.bin"
        ).joinToString("/")
    }

    private fun String.safePathSegment(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")
}
