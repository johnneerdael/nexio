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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ArtworkDecisionJsonStoreSnapshot(
    val decisions: List<ArtworkDecision>,
    val previewLinks: List<Pair<ArtworkDecisionKey, ArtworkDecisionKey>>,
    val storedSchemaVersion: Int?,
    val droppedDecisionCount: Int,
    val quarantinedDecisionCount: Int,
    val firstQuarantinedDecisionKeyHash: String?
)

internal class ArtworkDecisionJsonStoreDecodeException(
    val errorClassForLoad: String,
    val reason: String,
    val storedSchemaVersion: Int?,
    val droppedDecisionCount: Int,
    val quarantinedDecisionCount: Int
) : IllegalStateException(errorClassForLoad)

class ArtworkDecisionJsonCodec(private val gson: Gson) {
    fun readStoreFile(file: File): ArtworkDecisionJsonStoreSnapshot? {
        if (!file.isFile) {
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

    fun writeStoreFile(
        file: File,
        decisions: List<ArtworkDecision>,
        previewLinks: List<Pair<ArtworkDecisionKey, ArtworkDecisionKey>>
    ) {
        var tempFile: File? = null
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()

            tempFile = File(parent ?: File("."), "${file.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        gson.toJson(toStoreJson(decisions, previewLinks), writer)
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
        } catch (error: Exception) {
            tempFile?.delete()
            throw error
        }
    }

    fun decodeStoreJson(storeJson: JsonObject): ArtworkDecisionJsonStoreSnapshot {
        val storedSchemaVersion = storeJson.intOrNull("schemaVersion")
        val legacyObfuscatedStore = storedSchemaVersion == null && storeJson.has("a")
        if (storedSchemaVersion == null && !legacyObfuscatedStore) {
            throw ArtworkDecisionJsonStoreDecodeException(
                errorClassForLoad = "MissingSchemaVersion",
                reason = "missing_schema_version",
                storedSchemaVersion = null,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 0
            )
        }

        val decisionElements =
            if (legacyObfuscatedStore) {
                storeJson.requiredArray("a")
            } else {
                storeJson.requiredArray("decisions")
            }
        if (storedSchemaVersion != null && storedSchemaVersion != SCHEMA_VERSION) {
            val droppedDecisionCount = decisionElements.size()
            throw ArtworkDecisionJsonStoreDecodeException(
                errorClassForLoad = "SchemaVersionMismatch",
                reason = "schema_version_mismatch",
                storedSchemaVersion = storedSchemaVersion,
                droppedDecisionCount = droppedDecisionCount,
                quarantinedDecisionCount = droppedDecisionCount
            )
        }

        var droppedDecisionCount = 0
        var quarantinedDecisionCount = 0
        var firstQuarantinedDecisionKeyHash: String? = null
        val decisions = mutableListOf<ArtworkDecision>()
        decisionElements.forEach { decisionElement ->
            val restored = runCatching {
                decisionElement.asJsonObjectOrNull()?.let(::fromDecisionJson)
            }.getOrNull()
            if (restored == null) {
                droppedDecisionCount += 1
                quarantinedDecisionCount += 1
                if (firstQuarantinedDecisionKeyHash == null) {
                    firstQuarantinedDecisionKeyHash = decisionElement.safeDecisionKeyHash()
                }
            } else {
                decisions += restored
            }
        }

        val previewLinkElements =
            if (legacyObfuscatedStore) {
                storeJson.requiredArray("b")
            } else {
                storeJson.requiredArray("previewLinks")
            }
        val previewLinks = mutableListOf<Pair<ArtworkDecisionKey, ArtworkDecisionKey>>()
        previewLinkElements.forEach { linkElement ->
            runCatching {
                val link = requireNotNull(linkElement.asJsonObjectOrNull())
                previewLinks += ArtworkDecisionKey(requireNotNull(link.stringOrNull("previewKey", "a"))) to
                    ArtworkDecisionKey(requireNotNull(link.stringOrNull("canonicalKey", "b")))
            }.onFailure {
                droppedDecisionCount += 1
                quarantinedDecisionCount += 1
                if (firstQuarantinedDecisionKeyHash == null) {
                    firstQuarantinedDecisionKeyHash = linkElement.safePreviewLinkKeyHash()
                }
            }
        }

        return ArtworkDecisionJsonStoreSnapshot(
            decisions = decisions,
            previewLinks = previewLinks,
            storedSchemaVersion = if (legacyObfuscatedStore) SCHEMA_VERSION else storedSchemaVersion,
            droppedDecisionCount = droppedDecisionCount,
            quarantinedDecisionCount = quarantinedDecisionCount,
            firstQuarantinedDecisionKeyHash = firstQuarantinedDecisionKeyHash
        )
    }

    fun toStoreJson(
        decisions: List<ArtworkDecision>,
        previewLinks: List<Pair<ArtworkDecisionKey, ArtworkDecisionKey>>
    ): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        add("decisions", JsonArray().apply {
            decisions.forEach { decision -> add(toDecisionJson(decision)) }
        })
        add("previewLinks", JsonArray().apply {
            previewLinks.forEach { (previewKey, canonicalKey) ->
                add(JsonObject().apply {
                    addProperty("previewKey", previewKey.value)
                    addProperty("canonicalKey", canonicalKey.value)
                })
            }
        })
    }

    fun toDecisionJson(decision: ArtworkDecision): JsonObject = JsonObject().apply {
        addProperty("decisionKey", decision.decisionKey.value)
        add("owner", decision.ownerKey.toJson())
        addNullableProperty("canonicalContentId", decision.canonicalContentId)
        addProperty("imageType", decision.imageType.name)
        add("selectedCandidate", decision.selectedCandidate.toJson())
        add("rejectedCandidates", JsonArray().apply {
            decision.rejectedCandidates.forEach { rejected -> add(rejected.toJson()) }
        })
        addProperty("policyVersion", decision.policyVersion)
        addProperty("imageLanguage", decision.imageLanguage)
        addNullableProperty("settingsHash", decision.settingsHash)
        addNullableProperty("credentialHash", decision.credentialHash)
        addProperty("createdAtMs", decision.createdAtMs)
        addProperty("expiresAtMs", decision.expiresAtMs)
        addNullableProperty("staleUntilMs", decision.staleUntilMs)
    }

    fun fromDecisionJson(json: JsonObject): ArtworkDecision? = runCatching {
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey(requireNotNull(json.stringOrNull("decisionKey", "a"))),
            ownerKey = requireNotNull(json.objectOrNull("owner", "b")).toOwnerDomain(),
            canonicalContentId = json.stringOrNull("canonicalContentId", "c"),
            imageType = ArtworkType.valueOf(requireNotNull(json.stringOrNull("imageType", "d"))),
            selectedCandidate = requireNotNull(json.objectOrNull("selectedCandidate", "e")).toCandidateDomain(),
            rejectedCandidates = json.arrayOrEmpty("rejectedCandidates", "f").map { element ->
                requireNotNull(element.asJsonObjectOrNull()?.toRejectedDomainOrNull())
            },
            policyVersion = requireNotNull(json.intOrNull("policyVersion", "g")),
            imageLanguage = requireNotNull(json.stringOrNull("imageLanguage", "h")),
            settingsHash = json.stringOrNull("settingsHash", "i"),
            credentialHash = json.stringOrNull("credentialHash", "j"),
            createdAtMs = requireNotNull(json.longOrNull("createdAtMs", "k")),
            expiresAtMs = requireNotNull(json.longOrNull("expiresAtMs", "l")),
            staleUntilMs = json.longOrNull("staleUntilMs", "m")
        )
    }.getOrNull()

    private fun JsonObject.arrayOrEmpty(name: String, legacyName: String? = null): JsonArray =
        elementOrNull(name, legacyName)
            ?.takeUnless { it.isJsonNull }
            ?.asJsonArray
            ?: JsonArray()

    private fun JsonObject.requiredArray(name: String): JsonArray {
        val element = requireNotNull(get(name)) { "Missing required array $name" }
        require(!element.isJsonNull) { "Null required array $name" }
        return element.asJsonArray
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.elementOrNull(name: String, legacyName: String? = null): JsonElement? =
        get(name) ?: legacyName?.let(::get)

    private fun JsonObject.stringOrNull(name: String, legacyName: String? = null): String? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asString
        }.getOrNull()

    private fun JsonObject.intOrNull(name: String, legacyName: String? = null): Int? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asInt
        }.getOrNull()

    private fun JsonObject.longOrNull(name: String, legacyName: String? = null): Long? =
        runCatching {
            elementOrNull(name, legacyName)
                ?.takeUnless { it is JsonNull || it.isJsonNull }
                ?.asLong
        }.getOrNull()

    private fun JsonObject.objectOrNull(name: String, legacyName: String? = null): JsonObject? =
        runCatching {
            elementOrNull(name, legacyName)?.asJsonObjectOrNull()
        }.getOrNull()

    private fun JsonObject.toOwnerDomain(): ArtworkOwnerKey = when (val type = requireNotNull(stringOrNull("type", "a"))) {
        "canonical" -> ArtworkOwnerKey.CanonicalContent(requireNotNull(stringOrNull("contentId", "b")))
        "preview" -> ArtworkOwnerKey.PreviewItem(
            itemKey = requireNotNull(stringOrNull("itemKey", "c")),
            sourcePayloadHash = requireNotNull(stringOrNull("sourcePayloadHash", "d"))
        )
        else -> error("Unknown owner type $type")
    }

    private fun JsonObject.toCandidateDomain(): PersistedArtworkCandidate =
        PersistedArtworkCandidate(
            provider = objectOrNull("provider", "a")?.toProviderDomain(),
            sourceRole = ArtworkSourceRole.valueOf(requireNotNull(stringOrNull("sourceRole", "b"))),
            sourceHash = stringOrNull("sourceHash", "c"),
            redactedSourceForTrace = stringOrNull("redactedSourceForTrace", "d"),
            providerTemplate = objectOrNull("providerTemplate", "e")?.toTemplateDomain(),
            priority = requireNotNull(intOrNull("priority", "f"))
        )

    private fun JsonObject.toRejectedDomainOrNull(): RejectedArtworkCandidate? = runCatching {
        RejectedArtworkCandidate(
            provider = objectOrNull("provider", "a")?.toProviderDomain(),
            sourceRole = ArtworkSourceRole.valueOf(requireNotNull(stringOrNull("sourceRole", "b"))),
            reason = requireNotNull(stringOrNull("reason", "c")),
            sourceHash = stringOrNull("sourceHash", "d"),
            redactedSourceForTrace = stringOrNull("redactedSourceForTrace", "e"),
            providerTemplate = objectOrNull("providerTemplate", "f")?.toTemplateDomain(),
            priority = requireNotNull(intOrNull("priority", "g"))
        )
    }.getOrNull()

    private fun JsonObject.toTemplateDomain(): PersistedProviderTemplate =
        PersistedProviderTemplate(
            provider = requireNotNull(objectOrNull("provider", "a")).toProviderDomain(),
            imageType = ArtworkType.valueOf(requireNotNull(stringOrNull("imageType", "b"))),
            idType = requireNotNull(stringOrNull("idType", "c")),
            mediaId = requireNotNull(stringOrNull("mediaId", "d")),
            providerPathHash = stringOrNull("providerPathHash", "e"),
            settingsHash = stringOrNull("settingsHash", "f"),
            credentialHash = stringOrNull("credentialHash", "g"),
            imageLanguage = requireNotNull(stringOrNull("imageLanguage", "h")),
            policyVersion = requireNotNull(intOrNull("policyVersion", "i")),
            pathParams = objectOrNull("pathParams", "j")
                ?.entrySet()
                ?.associate { (key, value) -> key to value.asString }
                .orEmpty()
        )

    private fun JsonObject.toProviderDomain(): ArtworkProviderId = when (val type = requireNotNull(stringOrNull("type", "a"))) {
        "runtime" -> ArtworkProviderId.RuntimeProvider(
            IntegrationProvider.valueOf(requireNotNull(stringOrNull("integrationProvider", "b")))
        )
        "rail_preview" -> ArtworkProviderId.RailPreview
        "addon_preview" -> ArtworkProviderId.AddonPreview
        "placeholder" -> ArtworkProviderId.Placeholder
        else -> error("Unknown provider type $type")
    }

    private fun ArtworkOwnerKey.toJson(): JsonObject = JsonObject().apply {
        when (this@toJson) {
            is ArtworkOwnerKey.CanonicalContent -> {
                addProperty("type", "canonical")
                addProperty("contentId", contentId)
                add("itemKey", JsonNull.INSTANCE)
                add("sourcePayloadHash", JsonNull.INSTANCE)
            }
            is ArtworkOwnerKey.PreviewItem -> {
                addProperty("type", "preview")
                add("contentId", JsonNull.INSTANCE)
                addProperty("itemKey", itemKey)
                addProperty("sourcePayloadHash", sourcePayloadHash)
            }
        }
    }

    private fun PersistedArtworkCandidate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider?.toJson() ?: JsonNull.INSTANCE)
        addProperty("sourceRole", sourceRole.name)
        addNullableProperty("sourceHash", sourceHash)
        addNullableProperty("redactedSourceForTrace", redactedSourceForTrace)
        add("providerTemplate", providerTemplate?.toJson() ?: JsonNull.INSTANCE)
        addProperty("priority", priority)
    }

    private fun RejectedArtworkCandidate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider?.toJson() ?: JsonNull.INSTANCE)
        addProperty("sourceRole", sourceRole.name)
        addProperty("reason", reason)
        addNullableProperty("sourceHash", sourceHash)
        addNullableProperty("redactedSourceForTrace", redactedSourceForTrace)
        add("providerTemplate", providerTemplate?.toJson() ?: JsonNull.INSTANCE)
        addProperty("priority", priority)
    }

    private fun PersistedProviderTemplate.toJson(): JsonObject = JsonObject().apply {
        add("provider", provider.toJson())
        addProperty("imageType", imageType.name)
        addProperty("idType", idType)
        addProperty("mediaId", mediaId)
        addNullableProperty("providerPathHash", providerPathHash)
        addNullableProperty("settingsHash", settingsHash)
        addNullableProperty("credentialHash", credentialHash)
        addProperty("imageLanguage", imageLanguage)
        addProperty("policyVersion", policyVersion)
        add("pathParams", JsonObject().apply {
            pathParams.forEach { (key, value) -> addProperty(key, value) }
        })
    }

    private fun ArtworkProviderId.toJson(): JsonObject = JsonObject().apply {
        when (this@toJson) {
            is ArtworkProviderId.RuntimeProvider -> {
                addProperty("type", "runtime")
                addProperty("integrationProvider", providerId.name)
            }
            ArtworkProviderId.RailPreview -> {
                addProperty("type", "rail_preview")
                add("integrationProvider", JsonNull.INSTANCE)
            }
            ArtworkProviderId.AddonPreview -> {
                addProperty("type", "addon_preview")
                add("integrationProvider", JsonNull.INSTANCE)
            }
            ArtworkProviderId.Placeholder -> {
                addProperty("type", "placeholder")
                add("integrationProvider", JsonNull.INSTANCE)
            }
        }
    }

    private fun JsonObject.addNullableProperty(name: String, value: String?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonObject.addNullableProperty(name: String, value: Long?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonElement.safeDecisionKeyHash(): String {
        val decisionKey = runCatching {
            asJsonObject.get("decisionKey")?.takeIf { element -> element.isJsonPrimitive }?.asString
        }.getOrNull()
        return artworkDecisionShortSha256(decisionKey ?: toString())
    }

    private fun JsonElement.safePreviewLinkKeyHash(): String {
        val previewKey = runCatching {
            asJsonObject.get("previewKey")?.takeIf { element -> element.isJsonPrimitive }?.asString
        }.getOrNull()
        return artworkDecisionShortSha256(previewKey ?: toString())
    }

    companion object {
        internal const val SCHEMA_VERSION = 1
    }
}
