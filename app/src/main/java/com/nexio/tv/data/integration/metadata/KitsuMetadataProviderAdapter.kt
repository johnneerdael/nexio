package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.integration.KitsuApiShapes
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
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import javax.inject.Inject

class KitsuMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: KitsuIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.KITSU

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in kitsuShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val kitsuId = route.targetIds[MetadataPrimaryProvider.KITSU]
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val mediaKind = route.mediaKind.toContentMediaKind()
        val candidate = when (step.apiShapeId) {
            KitsuApiShapes.ANIME_CORE ->
                integrationProvider.fetchEnrichment(rawId = route.parentId, kitsuId = kitsuId, mediaKind = mediaKind) { resource ->
                    val attributes = resource.attributes ?: return@fetchEnrichment null
                    TvMetadataEnrichment(
                        seriesTvdbId = null,
                        localizedTitle = attributes.canonicalTitle,
                        description = attributes.synopsis ?: attributes.description,
                        backdrop = attributes.coverImage.bestUrl(),
                        poster = attributes.posterImage.bestUrl(),
                        releaseInfo = attributes.startDate,
                        runtimeMinutes = attributes.episodeLength,
                        ageRating = attributes.ageRating,
                        language = "ja",
                        status = attributes.status,
                        remoteIds = mapOf("kitsu" to setOf(kitsuId))
                    )
                }.toMetadataCandidate(this.provider).withKitsuCanonicalId(kitsuId)
            KitsuApiShapes.ANIME_EPISODES -> {
                integrationProvider.fetchEpisodeEnrichment(rawId = route.parentId, kitsuId = kitsuId, mediaKind = mediaKind) { emptyMap() }
                emptyCandidate(this.provider)
            }
            KitsuApiShapes.CASTINGS -> {
                integrationProvider.fetchCastings(route.parentId, kitsuId, mediaKind)
                emptyCandidate(this.provider)
            }
            KitsuApiShapes.ANIME_STAFF -> {
                integrationProvider.fetchAnimeStaff(route.parentId, kitsuId, mediaKind)
                emptyCandidate(this.provider)
            }
            KitsuApiShapes.ANIME_PRODUCTIONS -> {
                integrationProvider.fetchAnimeProductions(route.parentId, kitsuId, mediaKind)
                emptyCandidate(this.provider)
            }
            KitsuApiShapes.MEDIA_RELATIONSHIPS -> {
                integrationProvider.fetchMediaRelationships(route.parentId, kitsuId, mediaKind)
                emptyCandidate(this.provider)
            }
            else -> emptyCandidate(this.provider)
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private fun MetadataMediaKind.toContentMediaKind(): ContentMediaKind =
        if (this == MetadataMediaKind.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES

    private fun MetadataCandidate.withKitsuCanonicalId(kitsuId: String): MetadataCandidate =
        copy(fields = fields + (ResolvedField.CANONICAL_ID to FieldValue("kitsu:$kitsuId", FieldOwner.PRIMARY)))

    private companion object {
        val kitsuShapes = setOf(
            KitsuApiShapes.ANIME_CORE,
            KitsuApiShapes.ANIME_EPISODES,
            KitsuApiShapes.CASTINGS,
            KitsuApiShapes.ANIME_STAFF,
            KitsuApiShapes.ANIME_PRODUCTIONS,
            KitsuApiShapes.MEDIA_RELATIONSHIPS
        )
    }
}
