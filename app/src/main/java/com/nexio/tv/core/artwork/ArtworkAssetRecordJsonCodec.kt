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
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ArtworkAssetRecordJsonStoreSnapshot(
    val records: List<ArtworkAssetRecord>,
    val storedSchemaVersion: Int?,
    val quarantinedRecordCount: Int
)

class ArtworkAssetRecordJsonCodec(private val gson: Gson) {
    fun readStoreFile(file: File): ArtworkAssetRecordJsonStoreSnapshot? {
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        return FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    val token = try {
                        reader.peek()
                    } catch (_: EOFException) {
                        return@use null
                    }
                    val storeJson: JsonObject? = when (token) {
                        JsonToken.NULL -> {
                            reader.nextNull()
                            null
                        }
                        JsonToken.END_DOCUMENT -> null
                        else -> gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                    }
                    storeJson?.let(::decodeStoreJson)
                }
            }
        }
    }

    fun writeStoreFile(file: File, records: List<ArtworkAssetRecord>) {
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
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        gson.toJson(toStoreJson(records), writer)
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

    fun decodeStoreJson(storeJson: JsonObject): ArtworkAssetRecordJsonStoreSnapshot {
        val storedSchemaVersion = storeJson.intOrNull("schemaVersion")
        val legacyObfuscatedStore = storedSchemaVersion == null && storeJson.has("a")
        require(storedSchemaVersion != null || legacyObfuscatedStore) {
            "Missing artwork asset record schema"
        }
        val recordElements =
            if (legacyObfuscatedStore) {
                storeJson.requiredArray("a")
            } else {
                storeJson.requiredArray("records")
            }
        require(storedSchemaVersion == null || storedSchemaVersion == SCHEMA_VERSION) {
            "Unsupported artwork asset record schema $storedSchemaVersion"
        }

        var quarantinedRecordCount = 0
        val records = mutableListOf<ArtworkAssetRecord>()
        recordElements.forEach { element ->
            val record = runCatching {
                element.asJsonObjectOrNull()?.let(::fromRecordJson)
            }.getOrNull()
            if (record == null) {
                quarantinedRecordCount += 1
            } else {
                records += record
            }
        }

        return ArtworkAssetRecordJsonStoreSnapshot(
            records = records,
            storedSchemaVersion = if (legacyObfuscatedStore) SCHEMA_VERSION else storedSchemaVersion,
            quarantinedRecordCount = quarantinedRecordCount
        )
    }

    fun toStoreJson(records: List<ArtworkAssetRecord>): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        add("records", JsonArray().apply {
            records.forEach { record -> add(toRecordJson(record)) }
        })
    }

    fun toRecordJson(record: ArtworkAssetRecord): JsonObject = JsonObject().apply {
        addProperty("assetKey", record.assetKey.value)
        record.decisionKey?.let { decisionKey -> addProperty("decisionKey", decisionKey.value) }
        record.provider?.let { provider -> addProperty("provider", provider.toStoreString()) }
        addProperty("imageType", record.imageType.name)
        addProperty("imageLanguage", record.imageLanguage)
        addProperty("relativePath", record.relativePath)
        record.mimeType?.let { mimeType -> addProperty("mimeType", mimeType) }
        addProperty("byteCount", record.byteCount)
        addProperty("sourceHash", record.sourceHash)
        addProperty("policyVersion", record.policyVersion)
        addProperty("fetchedAtMs", record.fetchedAtMs)
        addProperty("expiresAtMs", record.expiresAtMs)
        addProperty("staleUntilMs", record.staleUntilMs)
    }

    fun fromRecordJson(json: JsonObject): ArtworkAssetRecord? = runCatching {
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey(requireNotNull(json.stringOrNull("assetKey", "a"))),
            decisionKey = json.stringOrNull("decisionKey", "b")?.let(::ArtworkDecisionKey),
            provider = json.stringOrNull("provider", "c").toProviderDomain(),
            imageType = ArtworkType.valueOf(requireNotNull(json.stringOrNull("imageType", "d"))),
            imageLanguage = requireNotNull(json.stringOrNull("imageLanguage", "e")),
            relativePath = requireNotNull(json.stringOrNull("relativePath", "f")),
            mimeType = json.stringOrNull("mimeType", "g"),
            byteCount = requireNotNull(json.longOrNull("byteCount", "h")),
            sourceHash = requireNotNull(json.stringOrNull("sourceHash", "i")),
            policyVersion = requireNotNull(json.intOrNull("policyVersion", "j")),
            fetchedAtMs = requireNotNull(json.longOrNull("fetchedAtMs", "k")),
            expiresAtMs = requireNotNull(json.longOrNull("expiresAtMs", "l")),
            staleUntilMs = requireNotNull(json.longOrNull("staleUntilMs", "m"))
        )
    }.getOrNull()

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val element = requireNotNull(get(name)) { "Missing required array $name" }
        require(!element.isJsonNull) { "Null required array $name" }
        return element.asJsonArray
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.elementOrNull(name: String, vararg legacyNames: String): JsonElement? {
        val canonical = get(name)
        if (canonical != null) return canonical
        for (i in legacyNames.indices) {
            val legacy = get(legacyNames[i])
            if (legacy != null) return legacy
        }
        return null
    }

    private fun JsonObject.stringOrNull(name: String, vararg legacyNames: String): String? =
        runCatching {
            elementOrNull(name, *legacyNames)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asString
        }.getOrNull()

    private fun JsonObject.intOrNull(name: String, vararg legacyNames: String): Int? =
        runCatching {
            elementOrNull(name, *legacyNames)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asInt
        }.getOrNull()

    private fun JsonObject.longOrNull(name: String, vararg legacyNames: String): Long? =
        runCatching {
            elementOrNull(name, *legacyNames)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asLong
        }.getOrNull()

    private fun ArtworkProviderId.toStoreString(): String =
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

    companion object {
        internal const val SCHEMA_VERSION = 1
    }
}
