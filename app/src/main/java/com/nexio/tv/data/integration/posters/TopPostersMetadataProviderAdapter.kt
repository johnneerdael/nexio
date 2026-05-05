package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter that injects a Top Posters poster artwork ref into the FieldResolver merge pipeline.
 *
 * Handles plan steps with [PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE] so that when
 * [com.nexio.tv.core.metadata.router.ProviderPlanExecutor] appends a TOP_POSTERS step to a
 * primary-provider plan, this adapter resolves the poster artwork ref via
 * [PosterRatingsUrlResolver] and emits a [MetadataCandidate] carrying
 * [ResolvedField.POSTER] with [FieldOwner.ARTWORK].
 *
 * The [FieldOwner.ARTWORK] designation means [com.nexio.tv.core.metadata.router.FieldResolver]
 * allows this candidate to replace a rail-preview poster placeholder while respecting the
 * standard "field already filled" rule against primary canonical poster values.
 *
 * Episode thumbnail artwork for anime is routed by [com.nexio.tv.core.metadata.router.MetadataRouterFacade]
 * through [PosterRatingsUrlResolver.resolveEpisodeThumbnailArtworkRef] using the episode coordinates
 * from the Kitsu episode metadata (which already carries correct seasonNumber for seasonal anime).
 */
@Singleton
class TopPostersMetadataProviderAdapter @Inject constructor(
    private val posterResolver: PosterRatingsUrlResolver
) : MetadataProviderAdapter {

    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TOP_POSTERS

    override fun supports(step: ProviderPlanStep): Boolean =
        step.apiShapeId == PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE

    override suspend fun execute(
        route: MetadataRoute,
        step: ProviderPlanStep
    ): ProviderStepResult {
        val settings = posterResolver.currentSettings()
        val stableContentId = route.premiumPosterStableContentId(IntegrationProvider.TOP_POSTERS)

        val poster = if (
            settings.selection.posterProvider == ArtworkProviderChoiceKey.TOP_POSTERS &&
            stableContentId != null
        ) {
            posterResolver.resolvePosterArtworkString(
                settings = settings,
                providerIds = route.premiumPosterProviderIds(),
                mediaKind = route.mediaKind,
                ownerKey = ArtworkOwnerKey.CanonicalContent(stableContentId)
            )
        } else {
            null
        }

        val candidate = if (poster != null) {
            MetadataCandidate(
                provider = MetadataPrimaryProvider.TOP_POSTERS,
                fields = mapOf(
                    ResolvedField.POSTER to FieldValue(
                        value = poster,
                        owner = FieldOwner.ARTWORK,
                        sourceRole = SourceRole.ARTWORK
                    )
                ),
                sourceProvider = "TOP_POSTERS",
                sourceRole = SourceRole.ARTWORK
            )
        } else {
            MetadataCandidate(provider = MetadataPrimaryProvider.TOP_POSTERS, fields = emptyMap())
        }

        return ProviderStepResult(step = step, candidate = candidate)
    }
}
