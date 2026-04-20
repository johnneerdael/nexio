package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRatingSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TitleRatingOverrideRepository @Inject constructor(
    private val customImdbTitleRatingsRepository: CustomImdbTitleRatingsRepository,
    private val mdbListRepository: MDBListRepository
) {
    suspend fun enrichPreview(preview: MetaPreview): MetaPreview {
        val customRating = customImdbTitleRatingsRepository.getTitleRating(
            contentId = preview.id,
            fallbackItemId = preview.id,
            contentType = preview.type,
            fallbackItemType = preview.apiType
        )
        if (customRating != null) {
            return preview.copy(
                imdbRating = customRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        }

        return mdbListRepository.enrichPreview(preview)
    }

    suspend fun enrichMeta(meta: Meta, fallbackItemId: String, fallbackItemType: String): Meta {
        val customRating = customImdbTitleRatingsRepository.getTitleRating(
            contentId = meta.id,
            fallbackItemId = fallbackItemId,
            contentType = meta.type,
            fallbackItemType = fallbackItemType
        )
        if (customRating != null) {
            return meta.copy(
                imdbRating = customRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        }

        val mdblistRating = mdbListRepository.getRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType
        )?.ratings?.imdb

        return if (mdblistRating != null) {
            meta.copy(
                imdbRating = mdblistRating.toFloat(),
                ratingSource = TitleRatingSource.IMDB
            )
        } else {
            meta
        }
    }
}
