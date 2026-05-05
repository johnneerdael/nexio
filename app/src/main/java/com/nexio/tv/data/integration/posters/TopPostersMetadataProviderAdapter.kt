package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeSourceIdentity
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.image.PosterIntegrationRequest
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter that injects a Top Posters URL into the FieldResolver merge pipeline.
 *
 * Handles plan steps with [PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE] so that when
 * [com.nexio.tv.core.metadata.router.ProviderPlanExecutor] appends a TOP_POSTERS step to a
 * primary-provider plan, this adapter resolves the poster URL via
 * [PosterRatingsUrlResolver] and emits a [MetadataCandidate] carrying
 * [ResolvedField.POSTER] with [FieldOwner.ARTWORK].
 *
 * The [FieldOwner.ARTWORK] designation means [com.nexio.tv.core.metadata.router.FieldResolver]
 * allows this candidate to replace a rail-preview poster placeholder while respecting the
 * standard "field already filled" rule against primary canonical poster values.
 *
 * For anime episode thumbnails, [resolveEpisodeThumbnailUrl] routes requests through
 * [AnimeSeasonProjectionResolver] (Correction 7 trigger condition) and constructs a
 * Top-Posters episode thumbnail URL using the projected TVDB coordinates. Returns null when
 * the projection has LOW confidence (flat-franchise), signalling the caller to fall back to
 * the primary provider thumbnail.
 */
@Singleton
class TopPostersMetadataProviderAdapter @Inject constructor(
    private val posterResolver: PosterRatingsUrlResolver,
    private val animeSeasonProjectionResolver: AnimeSeasonProjectionResolver,
) : MetadataProviderAdapter {

    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TOP_POSTERS

    override fun supports(step: ProviderPlanStep): Boolean =
        step.apiShapeId == PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE

    override suspend fun execute(
        route: MetadataRoute,
        step: ProviderPlanStep
    ): ProviderStepResult {
        val activeProvider = posterResolver.getActiveProvider()
            ?.takeIf { it.provider == com.nexio.tv.domain.model.PosterRatingsProvider.TOP_POSTERS }
        val stableContentId = route.premiumPosterStableContentId(MetadataPrimaryProvider.TOP_POSTERS)

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
                provider = MetadataPrimaryProvider.TOP_POSTERS,
                fields = mapOf(
                    ResolvedField.POSTER to FieldValue(
                        value = posterUrl,
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

    /**
     * Resolves a Top-Posters episode thumbnail URL for anime content using the projection layer.
     *
     * Trigger condition (Correction 7): anime projection is applied when EITHER signal is true:
     * - [route]`.provider == MetadataPrimaryProvider.KITSU`, OR
     * - [AnimeStremioId.parse]`(sourceContentId) != null`
     *
     * Non-anime content returns null immediately (no episode thumbnail support for non-anime here).
     *
     * For anime with a HIGH-confidence projection the returned URL encodes a
     * [PosterIntegrationRequest] using path `tvdb/thumbnail/{seriesId}/s{season}e{episode}.jpg`
     * where season and episode come from [AnimeEpisodeProjection.premiumArtworkCoordinate].
     *
     * Returns null when:
     * - the route is not anime content
     * - the projection has LOW confidence ([AnimeEpisodeProjection.premiumArtworkCoordinate] is null)
     */
    suspend fun resolveEpisodeThumbnailUrl(
        route: MetadataRoute?,
        sourceContentId: String,
        season: Int,
        episode: Int,
    ): String? {
        // Correction 7: use BOTH signals to detect anime
        val shouldUseAnimeProjection = route?.provider == MetadataPrimaryProvider.KITSU
            || AnimeStremioId.parse(sourceContentId) != null
        if (!shouldUseAnimeProjection) return null

        val activeProvider = posterResolver.getActiveProvider()
            ?.takeIf { it.provider == com.nexio.tv.domain.model.PosterRatingsProvider.TOP_POSTERS }
            ?: return null

        // Resolve AnimeSourceIdentity from the source content ID
        val kitsuId = resolveKitsuId(sourceContentId) ?: return null
        val identity = AnimeSourceIdentity(
            sourceKitsuId = kitsuId,
            animeStremioId = AnimeStremioId.parse(sourceContentId),
        )

        val work = animeSeasonProjectionResolver.resolveWork(identity)
        val sourceCoordinate = SourceEpisodeCoordinate(
            sourceKitsuId = kitsuId,
            season = season,
            episode = episode,
        )
        val projection = animeSeasonProjectionResolver.resolveEpisodeProjection(
            work = work,
            sourceEpisode = sourceCoordinate,
            target = EpisodeProjectionTarget.PREMIUM_THUMBNAIL,
        )

        // LOW confidence (flat-franchise) → return null so caller falls back to primary thumbnail
        val artworkCoordinate = projection.premiumArtworkCoordinate ?: return null

        val path = "tvdb/thumbnail/${artworkCoordinate.seriesId}/s${artworkCoordinate.season}e${artworkCoordinate.episode}.jpg"
        return PosterIntegrationRequest(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:tvdb:${artworkCoordinate.seriesId}:thumbnail:s${artworkCoordinate.season}e${artworkCoordinate.episode}:${stableHashHex8(activeProvider.apiKey)}",
            apiKey = activeProvider.apiKey,
            path = path,
            mimeType = "image/jpeg",
        ).toModel()
    }

    /**
     * Extracts the bare Kitsu numeric ID from a content ID of the form `kitsu:NNNN` or
     * a raw numeric string that already passed the `AnimeStremioId.parse` check.
     * Returns null for IDs that cannot be traced to a Kitsu source.
     */
    private fun resolveKitsuId(sourceContentId: String): String? {
        val trimmed = sourceContentId.trim()
        val parsed = AnimeStremioId.parse(trimmed) ?: return null
        return when (parsed.source) {
            com.nexio.tv.core.anime.AnimeIdSource.KITSU -> parsed.value
            else -> null
        }
    }

    private fun stableHashHex8(s: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return buildString(8) {
            for (i in 0 until 4) {
                val b = bytes[i].toInt() and 0xFF
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}

// toContentType() is defined in PosterAdapterUtils.kt (F2-13-E)
