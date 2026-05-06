package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.Video
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TitleRatingOverrideRepository @Inject constructor(
    private val customImdbTitleRatingsRepository: CustomImdbTitleRatingsRepository,
    private val mdbListRepository: MDBListRepository
) {
    suspend fun titleRatingCandidates(
        preview: MetaPreview,
        stableIdBundle: StableIdBundle? = null,
        providerIds: ProviderIds = ProviderIds()
    ): List<RatingCandidate> {
        val imdbId = stableIdBundle?.sidecars?.imdbId ?: providerIds.imdb
        val candidates = mutableListOf<RatingCandidate>()
        val customRating = imdbId
            ?.let { customImdbTitleRatingsRepository.getTitleRatingByImdbId(it) }
            ?: customImdbTitleRatingsRepository.getTitleRating(
                contentId = preview.id,
                fallbackItemId = preview.id,
                contentType = preview.type,
                fallbackItemType = preview.apiType
            )
        customRating?.toRatingCandidate(SourceRole.CUSTOM_IMDB, "IMDB")?.let(candidates::add)

        val mdblistRating = mdbListRepository.getRatingsForMeta(
            meta = preview.toRatingsMeta(),
            fallbackItemId = preview.id,
            fallbackItemType = preview.apiType,
            imdbIdOverride = imdbId
        )?.ratings?.imdb
        mdblistRating?.toRatingCandidate(SourceRole.MDBLIST, "MDBLIST")?.let(candidates::add)

        return candidates
    }

    suspend fun titleRatingCandidates(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        stableIdBundle: StableIdBundle? = null,
        providerIds: ProviderIds = ProviderIds()
    ): List<RatingCandidate> {
        val imdbId = stableIdBundle?.sidecars?.imdbId ?: providerIds.imdb
        val candidates = mutableListOf<RatingCandidate>()
        val customRating = imdbId
            ?.let { customImdbTitleRatingsRepository.getTitleRatingByImdbId(it) }
            ?: customImdbTitleRatingsRepository.getTitleRating(
                contentId = meta.id,
                fallbackItemId = fallbackItemId,
                contentType = meta.type,
                fallbackItemType = fallbackItemType
            )
        customRating?.toRatingCandidate(SourceRole.CUSTOM_IMDB, "IMDB")?.let(candidates::add)

        val mdblistRating = mdbListRepository.getRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            imdbIdOverride = imdbId
        )?.ratings?.imdb
        mdblistRating?.toRatingCandidate(SourceRole.MDBLIST, "MDBLIST")?.let(candidates::add)

        return candidates
    }

    private fun Double.toRatingCandidate(sourceRole: SourceRole, sourceProvider: String): RatingCandidate =
        RatingCandidate(
            value = this,
            sourceRole = sourceRole,
            sourceProvider = sourceProvider,
            confidence = Confidence.HIGH
        )

    private fun MetaPreview.toRatingsMeta(): Meta =
        Meta(
            id = id,
            type = type,
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            ratingSource = ratingSource,
            genres = genres,
            runtime = runtime,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = language,
            links = emptyList<MetaLink>(),
            trailerYtIds = trailerYtIds,
            posterProviderTag = posterProviderTag,
            artwork = artwork
        )
}
