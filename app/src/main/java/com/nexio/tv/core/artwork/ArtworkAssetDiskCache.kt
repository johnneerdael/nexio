package com.nexio.tv.core.artwork

import java.io.File
import java.io.IOException
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
        require(!record.assetKey.hasTraversalPathSegment()) {
            "Artwork asset key contains a traversal path segment: ${record.assetKey.value}"
        }
        val canonicalRecord = record.copy(relativePath = relativePathFor(record.assetKey))
        val file = requireCacheFile(canonicalRecord.relativePath)
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
        if (assetKey.hasTraversalPathSegment()) {
            return null
        }
        val file = cacheFileOrNull(relativePathFor(assetKey)) ?: return null
        return file.takeIfReadableFile()
    }

    fun getExistingFile(record: ArtworkAssetRecord): File? {
        val file = cacheFileOrNull(record.relativePath) ?: return null
        return file.takeIfReadableFile()
    }

    fun hasReadableImageBytes(record: ArtworkAssetRecord): Boolean {
        val file = getExistingFile(record) ?: return false
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(12)
                val count = input.read(buffer)
                val header = if (count <= 0) ByteArray(0) else buffer.copyOf(count)
                hasRecognizedImageHeader(header)
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
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

    private fun String.safePathSegment(): String {
        val segment = replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (segment.isBlank() || segment == "." || segment == "..") "_" else segment
    }

    private fun ArtworkAssetKey.hasTraversalPathSegment(): Boolean {
        val parts = value.split(":")
        return listOfNotNull(parts.getOrNull(1), parts.getOrNull(2))
            .any { it == "." || it == ".." }
    }

    private fun requireCacheFile(relativePath: String): File =
        requireNotNull(cacheFileOrNull(relativePath)) {
            "Artwork asset path escapes cache root: $relativePath"
        }

    private fun cacheFileOrNull(relativePath: String): File? {
        val canonicalRoot = cacheRoot.canonicalFile
        val file = File(cacheRoot, relativePath)
        val canonicalFile = File(canonicalRoot, relativePath).canonicalFile
        return file.takeIf { canonicalFile.toPath().startsWith(canonicalRoot.toPath()) }
    }

    private fun File.takeIfReadableFile(): File? =
        takeIf { it.isFile && it.canRead() }

    private fun hasRecognizedImageHeader(header: ByteArray): Boolean =
        isJpeg(header) || isPng(header) || isWebp(header)

    private fun isJpeg(header: ByteArray): Boolean =
        header.size >= 2 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte()

    private fun isPng(header: ByteArray): Boolean =
        header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()

    private fun isWebp(header: ByteArray): Boolean =
        header.size >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()

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
