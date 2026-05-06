package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.RatingResolver
import com.nexio.tv.core.metadata.router.resolver.RatingResolution
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDetailRatingDisplay
import com.nexio.tv.domain.model.ResolvedEpisodeRating
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.orDefault
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class DetailRatingDisplayContext(
    val meta: Meta,
    val fallbackItemId: String,
    val fallbackItemType: String,
    val episodesBySeason: Map<Int, Set<Int>>,
    val primaryProviderTitleRatingCandidate: RatingCandidate? = null,
    val previewFallbackTitleRatingCandidate: RatingCandidate? = null
)

class DetailRatingDisplayRepository private constructor(
    private val deps: Deps?
) {
    private data class Deps(
        val titleRatingOverrideRepository: TitleRatingOverrideRepository,
        val mdbListRepository: MDBListRepository,
        val episodeRatingsSelectionRepository: EpisodeRatingsSelectionRepository
    )

    @Inject
    constructor(
        titleRatingOverrideRepository: TitleRatingOverrideRepository,
        mdbListRepository: MDBListRepository,
        episodeRatingsSelectionRepository: EpisodeRatingsSelectionRepository
    ) : this(
        Deps(
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            mdbListRepository = mdbListRepository,
            episodeRatingsSelectionRepository = episodeRatingsSelectionRepository
        )
    )

    suspend fun resolve(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        providerIds: ProviderIds,
        episodesBySeason: Map<Int, Set<Int>>,
        primaryProviderTitleRatingCandidate: RatingCandidate? = null,
        previewFallbackTitleRatingCandidate: RatingCandidate? = null
    ): ResolvedDetailRatingDisplay {
        val dependencies = deps
        val ratingFallbackItemId = providerIds.imdb?.takeIf { it.isNotBlank() } ?: fallbackItemId
        val titleCandidates = buildList {
            addAll(
                dependencies?.let { availableDeps ->
                    availableDeps.titleRatingOverrideRepository.safeTitleRatingCandidates(
                        meta = meta,
                        fallbackItemId = ratingFallbackItemId,
                        fallbackItemType = fallbackItemType,
                        providerIds = providerIds
                    )
                }.orEmpty()
            )
            primaryProviderTitleRatingCandidate?.let(::add)
            previewFallbackTitleRatingCandidate?.let(::add)
            if (primaryProviderTitleRatingCandidate == null && previewFallbackTitleRatingCandidate == null) {
                meta.toPreviewFallbackRatingCandidate()?.let(::add)
            }
        }
        val titleRating = RatingResolver.resolveTitleRating(titleCandidates)
            ?.toTitleRating()

        val mdbListResult = dependencies?.let { availableDeps ->
            runOptional {
                availableDeps.mdbListRepository.getRatingsForMeta(
                    meta = meta,
                    fallbackItemId = ratingFallbackItemId,
                    fallbackItemType = fallbackItemType
                )
            }
        }
        val episodeCandidates = dependencies?.let { availableDeps ->
            runOptional {
                availableDeps.episodeRatingsSelectionRepository.episodeRatingCandidates(
                    meta = meta,
                    fallbackItemId = ratingFallbackItemId,
                    fallbackItemType = fallbackItemType,
                    episodesBySeason = episodesBySeason
                )
            }
        }.orEmpty()
        val episodeRatings = RatingResolver.resolveEpisodeRatings(episodeCandidates)

        return ResolvedDetailRatingDisplay(
            titleRating = titleRating,
            mdbListRatings = mdbListResult?.ratings,
            showMdbListImdb = mdbListResult?.hasImdbRating == true,
            episodeRatings = episodeRatings.mapValues { (_, rating) ->
                ResolvedEpisodeRating(
                    value = rating.value,
                    source = rating.toEpisodeRatingSourceName()
                )
            }
        )
    }

    private suspend fun TitleRatingOverrideRepository.safeTitleRatingCandidates(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        providerIds: ProviderIds
    ): List<RatingCandidate> =
        runOptional {
            titleRatingCandidates(
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                providerIds = providerIds
            )
        }.orEmpty()

    companion object {
        fun noOp(): DetailRatingDisplayRepository =
            DetailRatingDisplayRepository(deps = null)
    }
}

private inline fun <T> runOptional(block: () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private fun Meta.toPreviewFallbackRatingCandidate(): RatingCandidate? =
    imdbRating?.takeIf { it > 0.0f }?.let { value ->
        RatingCandidate(
            value = value.toDouble(),
            sourceRole = SourceRole.PREVIEW_FALLBACK,
            sourceProvider = ratingSource.orDefault().name,
            confidence = Confidence.LOW
        )
    }

private fun RatingResolution.toTitleRating(): TitleRating =
    TitleRating(value = value, source = toTitleRatingSource())

private fun RatingResolution.toTitleRatingSource(): com.nexio.tv.domain.model.TitleRatingSource =
    when (sourceRole) {
        SourceRole.CUSTOM_IMDB, SourceRole.MDBLIST, SourceRole.OMDB -> com.nexio.tv.domain.model.TitleRatingSource.IMDB
        SourceRole.PRIMARY_PROVIDER, SourceRole.PREVIEW_FALLBACK -> sourceProvider.toTitleRatingSource()
    }

private fun String.toTitleRatingSource(): com.nexio.tv.domain.model.TitleRatingSource =
    when (trim().uppercase()) {
        "TMDB", "TMDB_RATING" -> com.nexio.tv.domain.model.TitleRatingSource.TMDB
        else -> com.nexio.tv.domain.model.TitleRatingSource.IMDB
    }

private fun RatingResolution.toEpisodeRatingSourceName(): String =
    when (sourceRole) {
        SourceRole.CUSTOM_IMDB -> "IMDB"
        SourceRole.OMDB -> "OMDB"
        SourceRole.MDBLIST, SourceRole.PRIMARY_PROVIDER, SourceRole.PREVIEW_FALLBACK -> sourceProvider
    }
