package com.nexio.tv.core.artwork

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    fun write(record: ArtworkAssetRecord, bytes: ByteArray): ArtworkAssetDiskWrite {
        val canonicalRecord = record.copy(relativePath = relativePathFor(record.assetKey))
        val file = File(cacheRoot, canonicalRecord.relativePath)
        val parent = requireNotNull(file.parentFile) { "Artwork asset file must have a parent directory" }
        parent.mkdirs()
        val tempFile = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            tempFile.writeBytes(bytes)
            moveTempFile(tempFile, file)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        return ArtworkAssetDiskWrite(file = file, record = canonicalRecord)
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

    private fun moveTempFile(tempFile: File, file: File) {
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

data class ArtworkAssetDiskWrite(
    val file: File,
    val record: ArtworkAssetRecord
)
