package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.tvdb.TvdbTrailerLookupResult
import com.nexio.tv.core.tvdb.TvdbTrailerResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TVDB trailer adapter. Delegates to [TvdbTrailerResolver] which only supports TV/series-typed
 * content. Movie routes will short-circuit to an empty candidate.
 *
 * Emits a [ResolvedField.TRAILERS] candidate carrying either a YouTube URL string or a
 * playback/external URL — the [com.nexio.tv.core.metadata.router.resolver.TrailerResolver]
 * picks across providers; downstream consumers normalize the value into a playback source.
 */
@Singleton
class TvdbTrailerMetadataAdapter @Inject constructor(
    private val tvdbTrailerResolver: TvdbTrailerResolver
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId == TvdbApiShapes.TV_TRAILERS

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        if (route.mediaKind != MetadataMediaKind.SERIES) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        val tvdbId = MetadataProviderTargetIds.tvdbInt(route.targetIds[MetadataPrimaryProvider.TVDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val contentId = "tvdb:$tvdbId"
        val trailerUrl: String? = when (val lookup = tvdbTrailerResolver.resolveTitleTrailer(
            contentId = contentId,
            type = "tv",
            title = null,
            year = null
        )) {
            is TvdbTrailerLookupResult.ResolvedYouTube -> lookup.youtubeUrl
            is TvdbTrailerLookupResult.Resolved -> when (val result = lookup.result) {
                is com.nexio.tv.data.trailer.TrailerResolutionResult.External -> result.url
                is com.nexio.tv.data.trailer.TrailerResolutionResult.Playback -> result.source.videoUrl
            }
            else -> null
        }
        val trailers = listOfNotNull(trailerUrl)
        if (trailers.isEmpty()) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.TRAILERS,
                fields = mapOf(
                    ResolvedField.TRAILERS to FieldValue(trailers, FieldOwner.TRAILERS)
                )
            )
        )
    }
}
