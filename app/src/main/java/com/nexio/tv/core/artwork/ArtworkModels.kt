package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

@JvmInline
value class ArtworkDecisionKey(val value: String) {
    init { require(value.isNotBlank()) { "ArtworkDecisionKey must not be blank" } }
}

@JvmInline
value class ArtworkAssetKey(val value: String) {
    init { require(value.isNotBlank()) { "ArtworkAssetKey must not be blank" } }
}

@JvmInline
value class SensitiveArtworkUrl private constructor(val value: String) {
    override fun toString(): String = "<redacted-artwork-url>"

    companion object {
        fun of(raw: String): SensitiveArtworkUrl {
            require(raw.isNotBlank()) { "SensitiveArtworkUrl raw value must not be blank" }
            return SensitiveArtworkUrl(raw)
        }
    }
}

enum class ArtworkType { POSTER, BACKDROP, LOGO, THUMBNAIL }

enum class ArtworkSourceRole {
    PREMIUM,
    PRIMARY,
    CURRENT_PREVIEW,
    OTHER_PREVIEW,
    RAIL_PREVIEW,
    ADDON_PREVIEW,
    FALLBACK,
    PLACEHOLDER,
    LEGACY_STRING_COMPAT
}

enum class PlaceholderType { POSTER, BACKDROP, LOGO, THUMBNAIL }

sealed interface ArtworkProviderId {
    val key: String

    data class RuntimeProvider(val providerId: IntegrationProvider) : ArtworkProviderId {
        override val key: String = providerId.name
    }

    data object RailPreview : ArtworkProviderId { override val key: String = "RAIL_PREVIEW" }
    data object AddonPreview : ArtworkProviderId { override val key: String = "ADDON_PREVIEW" }
    data object Placeholder : ArtworkProviderId { override val key: String = "PLACEHOLDER" }
}

sealed interface ArtworkOwnerKey {
    data class CanonicalContent(val contentId: String) : ArtworkOwnerKey
    data class PreviewItem(val itemKey: String, val sourcePayloadHash: String) : ArtworkOwnerKey
}

data class ArtworkTrace(
    val selectedProvider: String? = null,
    val sourceRole: String? = null,
    val reason: String? = null,
    val rejectedCandidates: List<RejectedArtworkCandidate> = emptyList()
) {
    companion object {
        fun empty(): ArtworkTrace = ArtworkTrace()
    }
}

data class ArtworkBundle(
    val poster: ArtworkDisplayRef? = null,
    val backdrop: ArtworkDisplayRef? = null,
    val logo: ArtworkDisplayRef? = null,
    val thumbnail: ArtworkDisplayRef? = null
)

sealed interface ArtworkDisplayRef {
    val imageType: ArtworkType
    val trace: ArtworkTrace

    data class RuntimeAsset(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey?,
        override val imageType: ArtworkType,
        val selectedProvider: ArtworkProviderId?,
        val sourceRole: ArtworkSourceRole,
        override val trace: ArtworkTrace
    ) : ArtworkDisplayRef

    data class Placeholder(
        val placeholderType: PlaceholderType,
        override val imageType: ArtworkType,
        override val trace: ArtworkTrace
    ) : ArtworkDisplayRef
}

data class ArtworkCandidate(
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: String?,
    val providerIds: ProviderIds = ProviderIds(),
    val mediaKind: MetadataMediaKind = MetadataMediaKind.UNKNOWN,
    val imageType: ArtworkType,
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val source: ArtworkSource,
    val priority: Int,
    val requiresRuntimeFetch: Boolean,
    val imageLanguage: String = "en",
    val trace: ArtworkTrace = ArtworkTrace.empty()
)

sealed interface ArtworkSource {
    class RemoteUrl(
        val rawUrl: SensitiveArtworkUrl,
        val redactedUrlForTrace: String,
        val normalizedUrlHash: String
    ) : ArtworkSource {
        override fun toString(): String =
            "RemoteUrl(redactedUrlForTrace=$redactedUrlForTrace, normalizedUrlHash=$normalizedUrlHash)"
    }

    data class ProviderTemplate(
        val provider: ArtworkProviderId,
        val idType: String,
        val mediaId: String,
        val providerPathHash: String?,
        val settingsHash: String?,
        val credentialHash: String?
    ) : ArtworkSource

    data class LocalAsset(val assetKey: ArtworkAssetKey) : ArtworkSource
    data class Placeholder(val placeholderType: PlaceholderType) : ArtworkSource
}

data class ArtworkDecision(
    val decisionKey: ArtworkDecisionKey,
    val ownerKey: ArtworkOwnerKey,
    val canonicalContentId: String?,
    val imageType: ArtworkType,
    val selectedCandidate: PersistedArtworkCandidate,
    val rejectedCandidates: List<RejectedArtworkCandidate>,
    val policyVersion: Int,
    val imageLanguage: String = "en",
    val settingsHash: String?,
    val credentialHash: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long?
)

data class PersistedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val sourceHash: String?,
    val redactedSourceForTrace: String?,
    val providerTemplate: PersistedProviderTemplate?,
    val priority: Int
)

data class PersistedProviderTemplate(
    val provider: ArtworkProviderId,
    val imageType: ArtworkType,
    val idType: String,
    val mediaId: String,
    val providerPathHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String = "en",
    val policyVersion: Int
)

data class RejectedArtworkCandidate(
    val provider: ArtworkProviderId?,
    val sourceRole: ArtworkSourceRole,
    val reason: String
)

data class ArtworkAssetRecord(
    val assetKey: ArtworkAssetKey,
    val decisionKey: ArtworkDecisionKey?,
    val provider: ArtworkProviderId?,
    val imageType: ArtworkType,
    val imageLanguage: String = "en",
    val relativePath: String,
    val mimeType: String?,
    val byteCount: Long,
    val sourceHash: String,
    val policyVersion: Int,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long
)
