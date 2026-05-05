package com.nexio.tv.core.artwork

import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings

data class ArtworkRoutingPolicy(
    val settings: ArtworkProviderSettings = ArtworkProviderSettings(),
    val policyVersion: Int = 1
)

data class ArtworkSelectionResult(
    val selectedCandidateOrNull: ArtworkCandidate?,
    val rejectedCandidates: List<RejectedArtworkCandidate>
) {
    val selectedCandidate: ArtworkCandidate
        get() = selectedCandidateOrNull
            ?: throw IllegalArgumentException("ArtworkRouter has no selectable candidate")
}

class ArtworkRouter(
    private val capabilityResolver: ArtworkProviderCapabilityResolver = ArtworkProviderCapabilityResolver()
) {
    private val registry = ArtworkProviderRegistry()

    fun select(
        candidates: List<ArtworkCandidate>,
        policy: ArtworkRoutingPolicy
    ): ArtworkSelectionResult {
        require(candidates.isNotEmpty()) { "ArtworkRouter requires at least one candidate" }

        val context = SelectionContext.from(policy, registry)
        val rejectedBeforeSelection = candidates
            .mapNotNull { candidate -> candidate.unsupportedPremiumRejection(context) }

        val selected = candidates
            .filterNot { it.sourceRole == ArtworkSourceRole.PREMIUM && !it.isActiveSupportedPremium(context) }
            .minWithOrNull(candidateOrdering(context))
            ?: return ArtworkSelectionResult(
                selectedCandidateOrNull = null,
                rejectedCandidates = rejectedBeforeSelection
            )

        val selectedRank = selected.routingRank(context)
        val rejectedAfterSelection = candidates
            .filterNot { it === selected }
            .filterNot { candidate ->
                candidate.sourceRole == ArtworkSourceRole.PREMIUM && !candidate.isActiveSupportedPremium(context)
            }
            .map { candidate ->
                candidate.rejected(candidate.rejectionReasonForSelected(selectedRank))
            }

        return ArtworkSelectionResult(
            selectedCandidateOrNull = selected,
            rejectedCandidates = rejectedBeforeSelection + rejectedAfterSelection
        )
    }

    private fun candidateOrdering(context: SelectionContext): Comparator<ArtworkCandidate> =
        compareBy<ArtworkCandidate> { it.routingRank(context).precedence }
            .thenBy { it.priority }
            .thenBy { it.provider?.key.orEmpty() }
            .thenBy { it.sourceRole.name }
            .thenBy { it.canonicalContentId.orEmpty() }

    private fun ArtworkCandidate.routingRank(context: SelectionContext): RoutingRank =
        when {
            sourceRole == ArtworkSourceRole.PREMIUM &&
                isActiveSupportedPremium(context) -> RoutingRank.PREMIUM
            sourceRole == ArtworkSourceRole.PRIMARY -> RoutingRank.PRIMARY
            sourceRole == ArtworkSourceRole.CURRENT_PREVIEW -> RoutingRank.CURRENT_PREVIEW
            sourceRole == ArtworkSourceRole.OTHER_PREVIEW ||
                sourceRole == ArtworkSourceRole.RAIL_PREVIEW ||
                sourceRole == ArtworkSourceRole.ADDON_PREVIEW -> RoutingRank.OTHER_PREVIEW
            sourceRole == ArtworkSourceRole.PLACEHOLDER ||
                provider == ArtworkProviderId.Placeholder ||
                source is ArtworkSource.Placeholder -> RoutingRank.PLACEHOLDER
            else -> RoutingRank.FALLBACK
        }

    private fun ArtworkCandidate.isActiveSupportedPremium(context: SelectionContext): Boolean {
        val candidateProvider = provider ?: return false
        return candidateProvider == context.selectedProviderFor(imageType) &&
            candidateProvider.evaluatePremiumCandidate(this, context.policy).supported
    }

    private fun ArtworkCandidate.unsupportedPremiumRejection(context: SelectionContext): RejectedArtworkCandidate? {
        if (sourceRole != ArtworkSourceRole.PREMIUM) return null

        val candidateProvider = provider
        if (candidateProvider == null || candidateProvider != context.selectedProviderFor(imageType)) {
            return rejected("inactive_premium_artwork_provider_for_${imageType.name.lowercase()}")
        }

        val capability = candidateProvider.evaluatePremiumCandidate(this, context.policy)
        if (capability.supported) return null
        return rejected(capability.reason ?: "unsupported_premium_artwork_provider")
    }

    private fun ArtworkProviderId.evaluatePremiumCandidate(
        candidate: ArtworkCandidate,
        policy: ArtworkRoutingPolicy
    ): ArtworkProviderCapability =
        capabilityResolver.evaluate(
            provider = this,
            imageType = candidate.imageType,
            ids = candidate.providerIds,
            mediaKind = candidate.mediaKind,
            settings = policy.settings
        )

    private fun ArtworkCandidate.rejectionReasonForSelected(selectedRank: RoutingRank): String =
        when (selectedRank) {
            RoutingRank.PREMIUM -> "premium_artwork_provider_precedence"
            RoutingRank.PRIMARY -> "primary_provider_artwork_precedence"
            RoutingRank.CURRENT_PREVIEW -> "current_preview_artwork_precedence"
            RoutingRank.OTHER_PREVIEW -> "other_preview_artwork_precedence"
            RoutingRank.FALLBACK -> "fallback_artwork_precedence"
            RoutingRank.PLACEHOLDER -> "placeholder_artwork_precedence"
        }

    private fun ArtworkCandidate.rejected(reason: String): RejectedArtworkCandidate =
        RejectedArtworkCandidate(
            provider = provider,
            sourceRole = sourceRole,
            reason = reason
        )

    private enum class RoutingRank(val precedence: Int) {
        PREMIUM(0),
        PRIMARY(1),
        CURRENT_PREVIEW(2),
        OTHER_PREVIEW(3),
        FALLBACK(4),
        PLACEHOLDER(5)
    }

    private data class SelectionContext(
        val policy: ArtworkRoutingPolicy,
        val selectedProviders: Map<ArtworkType, ArtworkProviderId?>
    ) {
        fun selectedProviderFor(imageType: ArtworkType): ArtworkProviderId? =
            selectedProviders[imageType]

        companion object {
            fun from(
                policy: ArtworkRoutingPolicy,
                registry: ArtworkProviderRegistry
            ): SelectionContext =
                SelectionContext(
                    policy = policy,
                    selectedProviders = listOf(
                        ArtworkType.POSTER,
                        ArtworkType.BACKDROP,
                        ArtworkType.LOGO,
                        ArtworkType.THUMBNAIL
                    ).associateWith { type ->
                        val choice = policy.settings.selection.providerFor(type.toSettingsKey())
                        if (choice == ArtworkProviderChoiceKey.DEFAULT) {
                            null
                        } else {
                            registry.providerIdFor(choice)
                        }
                    }
                )
        }
    }
}
