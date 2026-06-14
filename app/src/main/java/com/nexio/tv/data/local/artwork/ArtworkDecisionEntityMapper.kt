package com.nexio.tv.data.local.artwork

import com.google.gson.JsonParser
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionJsonCodec
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId

class ArtworkDecisionEntityMapper(private val codec: ArtworkDecisionJsonCodec) {
    fun toEntity(decision: ArtworkDecision): ArtworkDecisionEntity {
        val ownerFields = decision.ownerKey.toEntityFields()
        return ArtworkDecisionEntity(
            decisionKey = decision.decisionKey.value,
            ownerType = ownerFields.ownerType,
            ownerContentId = ownerFields.ownerContentId,
            ownerItemKey = ownerFields.ownerItemKey,
            ownerSourcePayloadHash = ownerFields.ownerSourcePayloadHash,
            canonicalContentId = decision.canonicalContentId,
            imageType = decision.imageType.name,
            selectedProviderKey = decision.selectedCandidate.provider.toArtworkProviderKey(),
            selectedSourceRole = decision.selectedCandidate.sourceRole.name,
            settingsHash = decision.settingsHash,
            credentialHash = decision.credentialHash,
            policyVersion = decision.policyVersion,
            imageLanguage = decision.imageLanguage,
            createdAtMs = decision.createdAtMs,
            expiresAtMs = decision.expiresAtMs,
            staleUntilMs = decision.staleUntilMs,
            payloadJson = codec.toDecisionJson(decision).toString()
        )
    }

    fun toDomain(entity: ArtworkDecisionEntity): ArtworkDecision? =
        runCatching {
            JsonParser.parseString(entity.payloadJson)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(codec::fromDecisionJson)
        }.getOrNull()

    private fun ArtworkOwnerKey.toEntityFields(): OwnerEntityFields =
        when (this) {
            is ArtworkOwnerKey.CanonicalContent -> OwnerEntityFields(
                ownerType = OWNER_TYPE_CANONICAL,
                ownerContentId = contentId,
                ownerItemKey = null,
                ownerSourcePayloadHash = null
            )
            is ArtworkOwnerKey.PreviewItem -> OwnerEntityFields(
                ownerType = OWNER_TYPE_PREVIEW,
                ownerContentId = null,
                ownerItemKey = itemKey,
                ownerSourcePayloadHash = sourcePayloadHash
            )
        }

    private data class OwnerEntityFields(
        val ownerType: String,
        val ownerContentId: String?,
        val ownerItemKey: String?,
        val ownerSourcePayloadHash: String?
    )
}

internal fun ArtworkProviderId?.toArtworkProviderKey(): String? =
    when (this) {
        null -> null
        is ArtworkProviderId.RuntimeProvider -> providerId.name
        ArtworkProviderId.RailPreview -> "RAIL_PREVIEW"
        ArtworkProviderId.AddonPreview -> "ADDON_PREVIEW"
        ArtworkProviderId.Placeholder -> "PLACEHOLDER"
    }

private const val OWNER_TYPE_CANONICAL = "canonical"
private const val OWNER_TYPE_PREVIEW = "preview"
