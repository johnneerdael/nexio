package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.integration.IntegrationProvider
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DurableArtworkDecisionCache(
    private val file: File,
    private val gson: Gson
) : ArtworkDecisionCache {
    private val lock = Any()
    private var loaded = false
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        decisions[key]
    }

    override fun put(decision: ArtworkDecision) = synchronized(lock) {
        ensureLoadedLocked()
        decisions[decision.decisionKey] = decision
        persistLocked()
    }

    override fun remove(key: ArtworkDecisionKey) = synchronized(lock) {
        ensureLoadedLocked()
        decisions.remove(key)
        removeLinksForLocked(setOf(key))
        persistLocked()
    }

    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey] = canonicalKey
        persistLocked()
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey]?.let(decisions::get)
    }

    override fun invalidateBySettingsHash(settingsHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.settingsHash == settingsHash }
    }

    override fun invalidateByCredentialHash(credentialHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.credentialHash == credentialHash }
    }

    override fun invalidateArtworkPolicy(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes
        }
    }

    override fun invalidatePremiumArtworkPolicy() = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash != null || decision.credentialHash != null
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return

        runCatching {
            val raw = file.readText().takeIf { it.isNotBlank() } ?: return
            val dto = gson.fromJson(raw, StoreDto::class.java) ?: return
            if (dto.schemaVersion != SCHEMA_VERSION) return

            dto.decisions.orEmpty()
                .map { decision -> requireNotNull(decision.toDomainOrNull()) }
                .forEach { decision -> decisions[decision.decisionKey] = decision }
            dto.previewLinks.orEmpty().forEach { link ->
                previewToCanonical[ArtworkDecisionKey(link.previewKey)] =
                    ArtworkDecisionKey(link.canonicalKey)
            }
        }.onFailure {
            decisions.clear()
            previewToCanonical.clear()
        }
    }

    private fun invalidateMatchingLocked(matches: (ArtworkDecision) -> Boolean) {
        val deletedKeys = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { decision -> decision.decisionKey }
        if (deletedKeys.isEmpty()) return

        deletedKeys.forEach(decisions::remove)
        removeLinksForLocked(deletedKeys)
        persistLocked()
    }

    private fun removeLinksForLocked(keys: Set<ArtworkDecisionKey>) {
        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey in keys || canonicalKey in keys) {
                links.remove()
            }
        }
    }

    private fun persistLocked() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        val dto = StoreDto(
            schemaVersion = SCHEMA_VERSION,
            decisions = decisions.values.map(DecisionDto::fromDomain),
            previewLinks = previewToCanonical.map { (previewKey, canonicalKey) ->
                PreviewLinkDto(
                    previewKey = previewKey.value,
                    canonicalKey = canonicalKey.value
                )
            }
        )
        val tempFile = File(parent ?: File("."), "${file.name}.tmp")
        tempFile.writeText(gson.toJson(dto))
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

    private data class StoreDto(
        val schemaVersion: Int,
        val decisions: List<DecisionDto>?,
        val previewLinks: List<PreviewLinkDto>?
    )

    private data class PreviewLinkDto(
        val previewKey: String,
        val canonicalKey: String
    )

    private data class DecisionDto(
        val decisionKey: String,
        val owner: OwnerDto,
        val canonicalContentId: String?,
        val imageType: String,
        val selectedCandidate: CandidateDto,
        val rejectedCandidates: List<RejectedDto>?,
        val policyVersion: Int,
        val imageLanguage: String,
        val settingsHash: String?,
        val credentialHash: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val staleUntilMs: Long?
    ) {
        fun toDomainOrNull(): ArtworkDecision? = runCatching {
            ArtworkDecision(
                decisionKey = ArtworkDecisionKey(decisionKey),
                ownerKey = owner.toDomain(),
                canonicalContentId = canonicalContentId,
                imageType = ArtworkType.valueOf(imageType),
                selectedCandidate = selectedCandidate.toDomain(),
                rejectedCandidates = rejectedCandidates.orEmpty().map { rejected -> rejected.toDomain() },
                policyVersion = policyVersion,
                imageLanguage = imageLanguage,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                createdAtMs = createdAtMs,
                expiresAtMs = expiresAtMs,
                staleUntilMs = staleUntilMs
            )
        }.getOrNull()

        companion object {
            fun fromDomain(decision: ArtworkDecision): DecisionDto =
                DecisionDto(
                    decisionKey = decision.decisionKey.value,
                    owner = OwnerDto.fromDomain(decision.ownerKey),
                    canonicalContentId = decision.canonicalContentId,
                    imageType = decision.imageType.name,
                    selectedCandidate = CandidateDto.fromDomain(decision.selectedCandidate),
                    rejectedCandidates = decision.rejectedCandidates.map(RejectedDto::fromDomain),
                    policyVersion = decision.policyVersion,
                    imageLanguage = decision.imageLanguage,
                    settingsHash = decision.settingsHash,
                    credentialHash = decision.credentialHash,
                    createdAtMs = decision.createdAtMs,
                    expiresAtMs = decision.expiresAtMs,
                    staleUntilMs = decision.staleUntilMs
                )
        }
    }

    private data class OwnerDto(
        val type: String,
        val contentId: String?,
        val itemKey: String?,
        val sourcePayloadHash: String?
    ) {
        fun toDomain(): ArtworkOwnerKey = when (type) {
            "canonical" -> ArtworkOwnerKey.CanonicalContent(requireNotNull(contentId))
            "preview" -> ArtworkOwnerKey.PreviewItem(
                itemKey = requireNotNull(itemKey),
                sourcePayloadHash = requireNotNull(sourcePayloadHash)
            )
            else -> error("Unknown owner type $type")
        }

        companion object {
            fun fromDomain(owner: ArtworkOwnerKey): OwnerDto = when (owner) {
                is ArtworkOwnerKey.CanonicalContent -> OwnerDto(
                    type = "canonical",
                    contentId = owner.contentId,
                    itemKey = null,
                    sourcePayloadHash = null
                )
                is ArtworkOwnerKey.PreviewItem -> OwnerDto(
                    type = "preview",
                    contentId = null,
                    itemKey = owner.itemKey,
                    sourcePayloadHash = owner.sourcePayloadHash
                )
            }
        }
    }

    private data class CandidateDto(
        val provider: ProviderDto?,
        val sourceRole: String,
        val sourceHash: String?,
        val redactedSourceForTrace: String?,
        val providerTemplate: TemplateDto?,
        val priority: Int
    ) {
        fun toDomain(): PersistedArtworkCandidate =
            PersistedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(candidate: PersistedArtworkCandidate): CandidateDto =
                CandidateDto(
                    provider = candidate.provider?.let(ProviderDto::fromDomain),
                    sourceRole = candidate.sourceRole.name,
                    sourceHash = candidate.sourceHash,
                    redactedSourceForTrace = candidate.redactedSourceForTrace,
                    providerTemplate = candidate.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = candidate.priority
                )
        }
    }

    private data class RejectedDto(
        val provider: ProviderDto?,
        val sourceRole: String,
        val reason: String,
        val sourceHash: String?,
        val redactedSourceForTrace: String?,
        val providerTemplate: TemplateDto?,
        val priority: Int
    ) {
        fun toDomain(): RejectedArtworkCandidate =
            RejectedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                reason = reason,
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(rejected: RejectedArtworkCandidate): RejectedDto =
                RejectedDto(
                    provider = rejected.provider?.let(ProviderDto::fromDomain),
                    sourceRole = rejected.sourceRole.name,
                    reason = rejected.reason,
                    sourceHash = rejected.sourceHash,
                    redactedSourceForTrace = rejected.redactedSourceForTrace,
                    providerTemplate = rejected.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = rejected.priority
                )
        }
    }

    private data class TemplateDto(
        val provider: ProviderDto,
        val imageType: String,
        val idType: String,
        val mediaId: String,
        val providerPathHash: String?,
        val settingsHash: String?,
        val credentialHash: String?,
        val imageLanguage: String,
        val policyVersion: Int,
        val pathParams: Map<String, String>?
    ) {
        fun toDomain(): PersistedProviderTemplate =
            PersistedProviderTemplate(
                provider = provider.toDomain(),
                imageType = ArtworkType.valueOf(imageType),
                idType = idType,
                mediaId = mediaId,
                providerPathHash = providerPathHash,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = pathParams.orEmpty()
            )

        companion object {
            fun fromDomain(template: PersistedProviderTemplate): TemplateDto =
                TemplateDto(
                    provider = ProviderDto.fromDomain(template.provider),
                    imageType = template.imageType.name,
                    idType = template.idType,
                    mediaId = template.mediaId,
                    providerPathHash = template.providerPathHash,
                    settingsHash = template.settingsHash,
                    credentialHash = template.credentialHash,
                    imageLanguage = template.imageLanguage,
                    policyVersion = template.policyVersion,
                    pathParams = template.pathParams
                )
        }
    }

    private data class ProviderDto(
        val type: String,
        val integrationProvider: String?
    ) {
        fun toDomain(): ArtworkProviderId = when (type) {
            "runtime" -> ArtworkProviderId.RuntimeProvider(
                IntegrationProvider.valueOf(requireNotNull(integrationProvider))
            )
            "rail_preview" -> ArtworkProviderId.RailPreview
            "addon_preview" -> ArtworkProviderId.AddonPreview
            "placeholder" -> ArtworkProviderId.Placeholder
            else -> error("Unknown provider type $type")
        }

        companion object {
            fun fromDomain(provider: ArtworkProviderId): ProviderDto = when (provider) {
                is ArtworkProviderId.RuntimeProvider -> ProviderDto(
                    type = "runtime",
                    integrationProvider = provider.providerId.name
                )
                ArtworkProviderId.RailPreview -> ProviderDto(
                    type = "rail_preview",
                    integrationProvider = null
                )
                ArtworkProviderId.AddonPreview -> ProviderDto(
                    type = "addon_preview",
                    integrationProvider = null
                )
                ArtworkProviderId.Placeholder -> ProviderDto(
                    type = "placeholder",
                    integrationProvider = null
                )
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}
