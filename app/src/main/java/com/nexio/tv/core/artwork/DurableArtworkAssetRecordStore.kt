package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.integration.IntegrationProvider
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DurableArtworkAssetRecordStore(
    private val file: File,
    private val gson: Gson
) : ArtworkAssetRecordStore {
    private val lock = Any()
    private var loaded = false
    private var loadFailure: String? = null
    private var quarantinedRecords = 0
    private val recordsByAsset = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()
    private val latestByDecision = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()

    override fun put(record: ArtworkAssetRecord) = synchronized(lock) {
        ensureLoadedLocked()
        loadFailure?.let { failure ->
            throw IllegalStateException("Cannot write artwork asset records after failed load: $failure")
        }

        val candidateRecords = LinkedHashMap(recordsByAsset)
        candidateRecords[record.assetKey] = record
        val candidateLatest = latestByDecisionFor(candidateRecords)

        persistLocked(candidateRecords)

        recordsByAsset.clear()
        recordsByAsset.putAll(candidateRecords)
        latestByDecision.clear()
        latestByDecision.putAll(candidateLatest)
    }

    override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = synchronized(lock) {
        ensureLoadedLocked()
        recordsByAsset[assetKey]
    }

    override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
        synchronized(lock) {
            ensureLoadedLocked()
            latestByDecision[decisionKey]
        }

    fun quarantinedRecordCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        quarantinedRecords
    }

    private fun ensureLoadedLocked() {
        if (loaded) return

        if (!file.isFile) {
            loaded = true
            return
        }

        // CLAUDE.md hard rule #3: streaming read. The previous implementation
        // used `file.readText()` + `JsonParser.parseString(raw).asJsonObject`
        // which materialised the entire artwork-asset-record file as a String
        // (and pinned it through the parse via StringReader). With many tens of
        // thousands of artwork records this was a multi-MB cold-start spike.
        val recordElements = try {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            loaded = true
                            return
                        }
                        val storeJson = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                            ?: run { loaded = true; return }
                        val schemaVersion = requireNotNull(storeJson.intOrNull("schemaVersion"))
                        require(schemaVersion == SCHEMA_VERSION) {
                            "Unsupported artwork asset record schema $schemaVersion"
                        }
                        storeJson.requiredArray("records")
                    }
                }
            }
        } catch (exception: Exception) {
            recordsByAsset.clear()
            latestByDecision.clear()
            loadFailure = exception.message ?: exception::class.java.simpleName
            loaded = true
            return
        }

        quarantinedRecords = 0
        recordElements.forEach { element ->
            val record = runCatching {
                gson.fromJson(element, ArtworkAssetRecordDto::class.java).toDomain()
            }.getOrNull()
            if (record == null) {
                quarantinedRecords += 1
            } else {
                recordsByAsset[record.assetKey] = record
            }
        }
        rebuildLatestByDecisionLocked()
        loaded = true
    }

    private fun rebuildLatestByDecisionLocked() {
        latestByDecision.clear()
        latestByDecision.putAll(latestByDecisionFor(recordsByAsset))
    }

    private fun latestByDecisionFor(
        records: Map<ArtworkAssetKey, ArtworkAssetRecord>
    ): LinkedHashMap<ArtworkDecisionKey, ArtworkAssetRecord> {
        val latest = linkedMapOf<ArtworkDecisionKey, ArtworkAssetRecord>()
        records.values.forEach { record ->
            val decisionKey = record.decisionKey ?: return@forEach
            val current = latest[decisionKey]
            if (current == null || record.fetchedAtMs >= current.fetchedAtMs) {
                latest[decisionKey] = record
            }
        }
        return latest
    }

    private fun persistLocked(records: Map<ArtworkAssetKey, ArtworkAssetRecord>) {
        var tempFile: File? = null
        try {
            val parent = file.parentFile
            if (parent != null) {
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Unable to create artwork asset record directory ${parent.path}")
                }
                if (!parent.isDirectory) {
                    throw IOException("Artwork asset record parent is not a directory: ${parent.path}")
                }
            }

            tempFile = File.createTempFile("${file.name}.", ".tmp", parent ?: File("."))
            val storeDto = toStoreDto(records)
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        gson.toJson(storeDto, ArtworkAssetRecordStoreDto::class.java, writer)
                    }
                }
            }
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
        } finally {
            if (tempFile?.exists() == true) tempFile.delete()
        }
    }

    private fun toStoreDto(records: Map<ArtworkAssetKey, ArtworkAssetRecord>): ArtworkAssetRecordStoreDto =
        ArtworkAssetRecordStoreDto(
            schemaVersion = SCHEMA_VERSION,
            records = records.values.map { record -> record.toDto() }
        )

    private fun ArtworkAssetRecord.toDto(): ArtworkAssetRecordDto =
        ArtworkAssetRecordDto(
            assetKey = assetKey.value,
            decisionKey = decisionKey?.value,
            provider = provider?.toDto(),
            imageType = imageType.name,
            imageLanguage = imageLanguage,
            relativePath = relativePath,
            mimeType = mimeType,
            byteCount = byteCount,
            sourceHash = sourceHash,
            policyVersion = policyVersion,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = expiresAtMs,
            staleUntilMs = staleUntilMs
        )

    private fun ArtworkAssetRecordDto.toDomain(): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey(requireNotNull(assetKey)),
            decisionKey = decisionKey?.let(::ArtworkDecisionKey),
            provider = provider.toProviderDomain(),
            imageType = ArtworkType.valueOf(requireNotNull(imageType)),
            imageLanguage = requireNotNull(imageLanguage),
            relativePath = requireNotNull(relativePath),
            mimeType = mimeType,
            byteCount = requireNotNull(byteCount),
            sourceHash = requireNotNull(sourceHash),
            policyVersion = requireNotNull(policyVersion),
            fetchedAtMs = requireNotNull(fetchedAtMs),
            expiresAtMs = requireNotNull(expiresAtMs),
            staleUntilMs = requireNotNull(staleUntilMs)
        )

    private fun ArtworkProviderId.toDto(): String =
        when (this) {
            is ArtworkProviderId.RuntimeProvider -> providerId.name
            ArtworkProviderId.RailPreview -> "RAIL_PREVIEW"
            ArtworkProviderId.AddonPreview -> "ADDON_PREVIEW"
            ArtworkProviderId.Placeholder -> "PLACEHOLDER"
        }

    private fun String?.toProviderDomain(): ArtworkProviderId? =
        when (this) {
            null -> null
            "RAIL_PREVIEW" -> ArtworkProviderId.RailPreview
            "ADDON_PREVIEW" -> ArtworkProviderId.AddonPreview
            "PLACEHOLDER" -> ArtworkProviderId.Placeholder
            else -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.valueOf(this))
        }

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val element = requireNotNull(get(name)) { "Missing required array $name" }
        require(!element.isJsonNull) { "Null required array $name" }
        return element.asJsonArray
    }

    private fun JsonObject.intOrNull(name: String): Int? =
        runCatching {
            elementOrNull(name)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asInt
        }.getOrNull()

    private fun JsonObject.elementOrNull(name: String): JsonElement? = get(name)

    private data class ArtworkAssetRecordStoreDto(
        val schemaVersion: Int,
        val records: List<ArtworkAssetRecordDto>
    )

    private data class ArtworkAssetRecordDto(
        val assetKey: String?,
        val decisionKey: String?,
        val provider: String?,
        val imageType: String?,
        val imageLanguage: String?,
        val relativePath: String?,
        val mimeType: String?,
        val byteCount: Long?,
        val sourceHash: String?,
        val policyVersion: Int?,
        val fetchedAtMs: Long?,
        val expiresAtMs: Long?,
        val staleUntilMs: Long?
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
