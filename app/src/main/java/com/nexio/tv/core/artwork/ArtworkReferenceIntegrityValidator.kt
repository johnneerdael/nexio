package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

interface ArtworkReferenceIntegrityValidator {
    fun validate(ref: String?): ArtworkReferenceIntegrityResult
}

sealed interface ArtworkReferenceIntegrityResult {
    data object Empty : ArtworkReferenceIntegrityResult
    data class ValidDecision(val decisionKey: ArtworkDecisionKey) : ArtworkReferenceIntegrityResult
    data class ValidAsset(val assetKey: ArtworkAssetKey) : ArtworkReferenceIntegrityResult
    data class RecoverableAssetForDecision(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey
    ) : ArtworkReferenceIntegrityResult
    data class OrphanedDecisionRef(
        val decisionKey: ArtworkDecisionKey,
        val reason: String
    ) : ArtworkReferenceIntegrityResult
    data class UnknownDecisionRef(
        val decisionKey: ArtworkDecisionKey,
        val reason: String
    ) : ArtworkReferenceIntegrityResult
    data class Invalid(val reason: String) : ArtworkReferenceIntegrityResult
}

object NoopArtworkReferenceIntegrityValidator : ArtworkReferenceIntegrityValidator {
    override fun validate(ref: String?): ArtworkReferenceIntegrityResult {
        val value = ref?.trim().orEmpty()
        if (value.isBlank()) return ArtworkReferenceIntegrityResult.Empty

        if (value.isAssetRefRootOrChild()) {
            return value.parseArtworkAssetKeyOrNull()
                ?.let(ArtworkReferenceIntegrityResult::ValidAsset)
                ?: ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key")
        }
        if (value.isDecisionRefRootOrChild()) {
            return value.parseArtworkDecisionKeyOrNull()
                ?.let(ArtworkReferenceIntegrityResult::ValidDecision)
                ?: ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key")
        }
        if (value.isArtworkRefRootOrMalformed()) {
            return ArtworkReferenceIntegrityResult.Invalid("invalid_artwork_key_ref")
        }
        return ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref")
    }
}

class DefaultArtworkReferenceIntegrityValidator @Inject constructor(
    private val decisionCache: ArtworkDecisionCache,
    private val assetRecordStore: ArtworkAssetRecordStore,
    private val diskCache: ArtworkAssetDiskCache,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink
) : ArtworkReferenceIntegrityValidator {
    private val traceSequence = AtomicLong(0L)

    override fun validate(ref: String?): ArtworkReferenceIntegrityResult {
        val value = ref?.trim().orEmpty()
        if (value.isBlank()) return ArtworkReferenceIntegrityResult.Empty

        if (value.isAssetRefRootOrChild()) {
            val assetKey = value.parseArtworkAssetKeyOrNull()
                ?: return ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key")
            return validateAssetRef(assetKey)
        }
        if (value.isDecisionRefRootOrChild()) {
            val decisionKey = value.parseArtworkDecisionKeyOrNull()
                ?: return ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key")
            return validateDecisionRef(decisionKey)
        }
        if (value.isArtworkRefRootOrMalformed()) {
            return ArtworkReferenceIntegrityResult.Invalid("invalid_artwork_key_ref")
        }
        return ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref")
    }

    private fun validateAssetRef(assetKey: ArtworkAssetKey): ArtworkReferenceIntegrityResult {
        val record = runCatching {
            assetRecordStore.get(assetKey)
        }.getOrElse {
            traceRefIntegrityChecked(
                refKind = "asset",
                valid = false,
                payload = mapOf(
                    "assetKeyHash" to assetKey.hashedForTrace(),
                    "reason" to "asset_lookup_failed"
                ) + it.errorTracePayload()
            )
            return ArtworkReferenceIntegrityResult.Invalid("asset_lookup_failed")
        }
        val valid = if (record == null) {
            false
        } else {
            runCatching {
                diskCache.hasReadableImageBytes(record)
            }.getOrElse {
                traceRefIntegrityChecked(
                    refKind = "asset",
                    valid = false,
                    payload = mapOf(
                        "assetKeyHash" to assetKey.hashedForTrace(),
                        "reason" to "asset_read_failed"
                    ) + it.errorTracePayload()
                )
                return ArtworkReferenceIntegrityResult.Invalid("asset_read_failed")
            }
        }
        traceRefIntegrityChecked(
            refKind = "asset",
            valid = valid,
            payload = mapOf(
                "assetKeyHash" to assetKey.hashedForTrace(),
                "reason" to if (valid) null else "missing_or_unreadable_asset"
            )
        )
        return if (valid) {
            ArtworkReferenceIntegrityResult.ValidAsset(assetKey)
        } else {
            ArtworkReferenceIntegrityResult.Invalid("missing_or_unreadable_asset")
        }
    }

    private fun validateDecisionRef(decisionKey: ArtworkDecisionKey): ArtworkReferenceIntegrityResult {
        val lookupResult = runCatching {
            decisionCache.lookup(decisionKey, requiredContext = null)
        }.getOrElse {
            traceRefIntegrityChecked(
                refKind = "decision",
                valid = false,
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.hashedForTrace(),
                    "reason" to "lookup_failed"
                ) + it.errorTracePayload()
            )
            return ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                decisionKey = decisionKey,
                reason = "lookup_failed"
            )
        }

        return when (lookupResult) {
            is ArtworkDecisionLookupResult.Found ->
                ArtworkReferenceIntegrityResult.ValidDecision(lookupResult.decision.decisionKey)

            is ArtworkDecisionLookupResult.MissingAuthoritative ->
                validateMissingAuthoritativeDecision(decisionKey)

            is ArtworkDecisionLookupResult.CacheNotAuthoritative ->
                ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                    decisionKey = lookupResult.decisionKey,
                    reason = "decision_cache_not_authoritative"
                )

            is ArtworkDecisionLookupResult.LookupFailed ->
                ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                    decisionKey = lookupResult.decisionKey,
                    reason = "lookup_failed"
                )
        }
    }

    private fun validateMissingAuthoritativeDecision(
        decisionKey: ArtworkDecisionKey
    ): ArtworkReferenceIntegrityResult {
        val record = runCatching {
            assetRecordStore.findLatestAssetForDecision(decisionKey)
        }.getOrElse {
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_asset_lookup_failed",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.hashedForTrace(),
                    "reason" to "asset_reverse_index_lookup_failed"
                ) + it.errorTracePayload()
            )
            return ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                decisionKey = decisionKey,
                reason = "missing_authoritative_no_asset"
            )
        }
        val recoveryRecord = record?.takeIf { it.assetKey.hasGeneratedArtworkAssetShape() }
        val assetReadable = if (recoveryRecord == null) {
            false
        } else {
            runCatching {
                diskCache.hasReadableImageBytes(recoveryRecord)
            }.getOrElse {
                traceArtwork(
                    eventType = "artwork.orphan_decision_ref_asset_read_failed",
                    payload = mapOf(
                        "decisionKeyHash" to decisionKey.hashedForTrace(),
                        "assetKeyHash" to recoveryRecord.assetKey.hashedForTrace(),
                        "reason" to "asset_read_failed"
                    ) + it.errorTracePayload()
                )
                false
            }
        }
        if (recoveryRecord != null && assetReadable) {
            traceArtwork(
                eventType = "artwork.orphan_decision_ref_asset_recovered",
                payload = mapOf(
                    "decisionKeyHash" to decisionKey.hashedForTrace(),
                    "assetKeyHash" to recoveryRecord.assetKey.hashedForTrace(),
                    "source" to "asset_reverse_index"
                )
            )
            return ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(
                decisionKey = decisionKey,
                assetKey = recoveryRecord.assetKey
            )
        }

        traceArtwork(
            eventType = "artwork.orphan_decision_ref_found",
            payload = mapOf(
                "decisionKeyHash" to decisionKey.hashedForTrace(),
                "assetKeyHash" to recoveryRecord?.assetKey?.hashedForTrace(),
                "reason" to "missing_authoritative_no_asset"
            )
        )
        return ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
            decisionKey = decisionKey,
            reason = "missing_authoritative_no_asset"
        )
    }

    private fun traceRefIntegrityChecked(
        refKind: String,
        valid: Boolean,
        payload: Map<String, Any?>
    ) {
        traceArtwork(
            eventType = "artwork.ref_integrity_checked",
            payload = mapOf(
                "refKind" to refKind,
                "valid" to valid
            ) + payload
        )
    }

    private fun traceArtwork(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = traceSink.activeTraceSessionId() ?: LOGCAT_ONLY_TRACE_SESSION_ID,
                sequence = traceSequence.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }

    private fun ArtworkDecisionKey.hashedForTrace(): String =
        artworkDecisionShortSha256(value)

    private fun ArtworkAssetKey.hashedForTrace(): String =
        artworkDecisionShortSha256(value)

    private fun Throwable.errorTracePayload(): Map<String, String> =
        mapOf(
            "errorClass" to (this::class.simpleName ?: this::class.java.name),
            "messageHash" to artworkDecisionShortSha256(message.orEmpty())
        )

    private companion object {
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }
}

private const val ARTWORK_REF_SCHEME = "nexio-artwork:"
private const val ARTWORK_REF_ROOT = "$ARTWORK_REF_SCHEME//"
private const val ASSET_REF_ROOT = "${ARTWORK_REF_ROOT}asset"
private const val DECISION_REF_ROOT = "nexio-artwork://decision"
private const val ASSET_REF_PREFIX = "$ASSET_REF_ROOT/"
private const val DECISION_REF_PREFIX = "$DECISION_REF_ROOT/"

private fun String.isAssetRefRootOrChild(): Boolean =
    this == ASSET_REF_ROOT || startsWith(ASSET_REF_PREFIX)

private fun String.isDecisionRefRootOrChild(): Boolean =
    this == DECISION_REF_ROOT || startsWith(DECISION_REF_PREFIX)

private fun String.isArtworkRefRootOrMalformed(): Boolean =
    startsWith(ARTWORK_REF_SCHEME)

private fun String.parseArtworkAssetKeyOrNull(): ArtworkAssetKey? =
    parseArtworkKeyOrNull(ASSET_REF_PREFIX, ::ArtworkAssetKey)
        ?.takeIf { it.hasGeneratedArtworkAssetShape() }

private fun String.parseArtworkDecisionKeyOrNull(): ArtworkDecisionKey? =
    parseArtworkKeyOrNull(DECISION_REF_PREFIX, ::ArtworkDecisionKey)
        ?.takeIf { it.hasGeneratedArtworkDecisionShape() }

private fun <T> String.parseArtworkKeyOrNull(
    prefix: String,
    factory: (String) -> T
): T? {
    if (!startsWith(prefix)) return null
    return runCatching {
        factory(removePrefix(prefix))
    }.getOrNull()
}

private fun ArtworkAssetKey.hasGeneratedArtworkAssetShape(): Boolean {
    val parts = value.split(":")
    val provider = parts.getOrNull(1) ?: return false
    val imageType = parts.getOrNull(2) ?: return false
    val identityMarker = parts.getOrNull(3) ?: return false
    if (parts.firstOrNull() != "artwork-asset") return false
    if (!provider.isGeneratedProviderKey(allowNone = false)) return false
    if (!imageType.isRecognizedLowercaseImageType()) return false

    return when (identityMarker) {
        "urlHash" -> parts.hasRemoteUrlAssetShape()
        else -> parts.hasProviderTemplateAssetShape()
    }
}

private fun ArtworkDecisionKey.hasGeneratedArtworkDecisionShape(): Boolean {
    val parts = value.split(":")
    val providerMarkerIndex = parts.size - 12
    val imageType = parts.getOrNull(1) ?: return false
    if (parts.firstOrNull() != "artwork-decision") return false
    if (!imageType.isRecognizedLowercaseImageType()) return false
    if (providerMarkerIndex <= 2) return false
    if (parts.getOrNull(providerMarkerIndex) != "provider") return false
    if (parts.subList(2, providerMarkerIndex).hasGeneratedDecisionOwnerShape().not()) return false
    if (!parts.getOrNull(providerMarkerIndex + 1).isGeneratedProviderKey(allowNone = true)) return false
    if (parts.getOrNull(providerMarkerIndex + 2) != "premium") return false
    val premiumValue = parts.getOrNull(providerMarkerIndex + 3)
    if (premiumValue != "true" && premiumValue != "false") return false
    if (parts.getOrNull(providerMarkerIndex + 4) != "settings") return false
    if (!parts.getOrNull(providerMarkerIndex + 5).isGeneratedVariableToken()) return false
    if (parts.getOrNull(providerMarkerIndex + 6) != "credential") return false
    if (!parts.getOrNull(providerMarkerIndex + 7).isGeneratedVariableToken()) return false
    if (parts.getOrNull(providerMarkerIndex + 8) != "imageLang") return false
    if (parts.getOrNull(providerMarkerIndex + 9) != "en") return false
    if (parts.getOrNull(providerMarkerIndex + 10) != "policy") return false
    if (!parts.getOrNull(providerMarkerIndex + 11).isCanonicalIntToken()) return false

    return true
}

private fun List<String>.hasGeneratedDecisionOwnerShape(): Boolean =
    when (firstOrNull()) {
        "canonical" ->
            hasGeneratedCanonicalDecisionOwnerShape()
        "preview" ->
            hasGeneratedPreviewDecisionOwnerShape()
        else ->
            false
    }

private fun List<String>.hasGeneratedCanonicalDecisionOwnerShape(): Boolean {
    if (size < 2) return false

    return drop(1).all { it.isGeneratedVariableToken() }
}

private fun List<String>.hasGeneratedPreviewDecisionOwnerShape(): Boolean {
    if (size < 4) return false

    val payloadMarkerIndex = lastIndex - 1
    if (payloadMarkerIndex <= 1) return false
    if (getOrNull(payloadMarkerIndex) != "payload") return false

    val itemKeySegments = subList(1, payloadMarkerIndex)
    val payloadHash = lastOrNull()
    return itemKeySegments.all { it.isGeneratedVariableToken() } &&
        payloadHash.isGeneratedVariableToken()
}

private fun String?.isGeneratedProviderKey(allowNone: Boolean): Boolean {
    val value = this ?: return false
    return value.isGeneratedVariableToken() &&
        (value in generatedArtworkRuntimeProviderKeys ||
        value in generatedSyntheticProviderKeys ||
        (allowNone && value == "none"))
}

private fun List<String>.hasProviderTemplateAssetShape(): Boolean {
    if (size < 13) return false
    if (!getOrNull(3).isGeneratedVariableToken()) return false
    if (!getOrNull(4).isGeneratedVariableToken()) return false
    if (getOrNull(size - 8) != "settings") return false
    if (!getOrNull(size - 7).isGeneratedVariableToken()) return false
    if (getOrNull(size - 6) != "credential") return false
    if (!getOrNull(size - 5).isGeneratedVariableToken()) return false
    if (getOrNull(size - 4) != "imageLang") return false
    if (getOrNull(size - 3) != "en") return false
    if (getOrNull(size - 2) != "policy") return false
    if (!getOrNull(size - 1).isCanonicalIntToken()) return false

    val pathParams = subList(5, size - 8)
    return pathParams.hasCanonicalPathParams()
}

private fun List<String>.hasCanonicalPathParams(): Boolean {
    if (size % 2 != 0) return false

    val keys = mutableListOf<String>()
    chunked(2).forEach { (key, value) ->
        if (!key.isGeneratedVariableToken() || !value.isGeneratedVariableToken()) return false
        keys += key
    }

    return keys.distinct().size == keys.size && keys == keys.sorted()
}

private fun List<String>.hasRemoteUrlAssetShape(): Boolean =
    size == 11 &&
        getOrNull(3) == "urlHash" &&
        getOrNull(4).isGeneratedVariableToken() &&
        getOrNull(5) == "variant" &&
        getOrNull(6).isGeneratedVariableToken() &&
        getOrNull(7) == "imageLang" &&
        getOrNull(8) == "en" &&
        getOrNull(9) == "policy" &&
        getOrNull(10).isCanonicalIntToken()

private fun String.isRecognizedLowercaseImageType(): Boolean =
    this == lowercase() &&
        ArtworkType.entries.any { it.name.lowercase() == this }

private fun String?.isGeneratedVariableToken(): Boolean {
    val token = this ?: return false
    return token.isNotBlank() && token == token.trim()
}

private fun String?.isCanonicalIntToken(): Boolean {
    val token = this ?: return false
    val parsed = token.toIntOrNull() ?: return false
    return parsed.toString() == token
}

private val generatedSyntheticProviderKeys = setOf(
    "RAIL_PREVIEW",
    "ADDON_PREVIEW",
    "PLACEHOLDER"
)

private val generatedArtworkRuntimeProviderKeys = setOf(
    IntegrationProvider.TMDB.name,
    IntegrationProvider.TVDB.name,
    IntegrationProvider.KITSU.name,
    IntegrationProvider.OMDB.name,
    IntegrationProvider.RPDB.name,
    IntegrationProvider.TOP_POSTERS.name
)
