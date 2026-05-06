package com.nexio.tv.data.repository

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
    val episodesBySeason: Map<Int, Set<Int>>
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
        episodesBySeason: Map<Int, Set<Int>>
    ): ResolvedDetailRatingDisplay {
        val dependencies = deps ?: return ResolvedDetailRatingDisplay()
        val ratingFallbackItemId = providerIds.imdb?.takeIf { it.isNotBlank() } ?: fallbackItemId
        val enrichedMeta = runOptional {
            dependencies.titleRatingOverrideRepository.enrichMeta(
                meta = meta,
                fallbackItemId = ratingFallbackItemId,
                fallbackItemType = fallbackItemType
            )
        } ?: meta

        val mdbListResult = runOptional {
            dependencies.mdbListRepository.getRatingsForMeta(
                meta = enrichedMeta,
                fallbackItemId = ratingFallbackItemId,
                fallbackItemType = fallbackItemType
            )
        }
        val episodeRatings = runOptional {
            dependencies.episodeRatingsSelectionRepository.getEpisodeRatings(
                meta = enrichedMeta,
                fallbackItemId = ratingFallbackItemId,
                fallbackItemType = fallbackItemType,
                episodesBySeason = episodesBySeason
            )
        }.orEmpty()

        return ResolvedDetailRatingDisplay(
            titleRating = enrichedMeta.imdbRating?.toDouble()?.let { value ->
                TitleRating(value = value, source = enrichedMeta.ratingSource.orDefault())
            },
            mdbListRatings = mdbListResult?.ratings,
            showMdbListImdb = mdbListResult?.hasImdbRating == true,
            episodeRatings = episodeRatings.mapValues { (_, rating) ->
                ResolvedEpisodeRating(
                    value = rating.value,
                    source = rating.source.name
                )
            }
        )
    }

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
