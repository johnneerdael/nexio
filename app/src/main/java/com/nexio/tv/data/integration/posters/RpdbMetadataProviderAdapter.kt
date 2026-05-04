package com.nexio.tv.data.integration.posters

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter that injects an RPDB poster URL into the FieldResolver merge pipeline.
 *
 * Handles plan steps with [PosterApiShapes.RPDB_POSTER_TEMPLATE] so that when
 * [com.nexio.tv.core.metadata.router.ProviderPlanExecutor] appends an RPDB step to a
 * primary-provider plan, this adapter resolves the poster URL via
 * [PosterRatingsUrlResolver] and emits a [MetadataCandidate] carrying
 * [ResolvedField.POSTER] with [FieldOwner.ARTWORK].
 *
 * The [FieldOwner.ARTWORK] designation means [com.nexio.tv.core.metadata.router.FieldResolver]
 * allows this candidate to replace a rail-preview poster placeholder while respecting the
 * standard "field already filled" rule against primary canonical poster values.
 */
@Singleton
class RpdbMetadataProviderAdapter @Inject constructor(
    private val posterResolver: PosterRatingsUrlResolver
) : MetadataProviderAdapter {

    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.RPDB

    override fun supports(step: ProviderPlanStep): Boolean =
        step.apiShapeId == PosterApiShapes.RPDB_POSTER_TEMPLATE

    override suspend fun execute(
        route: MetadataRoute,
        step: ProviderPlanStep
    ): ProviderStepResult {
        val activeProvider = posterResolver.getActiveProvider()
            ?.takeIf { it.provider == com.nexio.tv.domain.model.PosterRatingsProvider.RPDB }
        val stableContentId = route.premiumPosterStableContentId()

        val posterUrl = if (activeProvider != null && stableContentId != null) {
            posterResolver.resolvePosterUrl(
                originalPosterUrl = null,
                contentId = stableContentId,
                contentType = route.mediaKind.toContentType(),
                activeProvider = activeProvider
            )
        } else {
            null
        }

        val candidate = if (posterUrl != null) {
            MetadataCandidate(
                provider = MetadataPrimaryProvider.RPDB,
                fields = mapOf(
                    ResolvedField.POSTER to FieldValue(
                        value = posterUrl,
                        owner = FieldOwner.ARTWORK,
                        sourceRole = SourceRole.ARTWORK
                    )
                ),
                sourceProvider = "RPDB",
                sourceRole = SourceRole.ARTWORK
            )
        } else {
            MetadataCandidate(provider = MetadataPrimaryProvider.RPDB, fields = emptyMap())
        }

        return ProviderStepResult(step = step, candidate = candidate)
    }
}

// toContentType() is defined in PosterAdapterUtils.kt (F2-13-E)
