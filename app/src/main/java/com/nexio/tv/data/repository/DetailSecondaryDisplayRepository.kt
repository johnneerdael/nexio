package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.domain.model.MetaReview
import javax.inject.Inject

class DetailSecondaryDisplayRepository private constructor(
    private val deps: Deps?
) {
    private data class Deps(
        val metadataSecondaryRepository: MetadataSecondaryRepository
    )

    @Inject
    constructor(
        metadataSecondaryRepository: MetadataSecondaryRepository
    ) : this(Deps(metadataSecondaryRepository = metadataSecondaryRepository))

    suspend fun fetchKitsuAdvancedDetail(
        rawId: String,
        mediaKind: ContentMediaKind,
        preferredLanguageCode: String?
    ): KitsuAdvancedAnimeDetail? =
        deps?.metadataSecondaryRepository?.fetchKitsuAdvancedDetail(
            rawId = rawId,
            mediaKind = mediaKind,
            preferredLanguageCode = preferredLanguageCode
        )

    suspend fun fetchKitsuReviews(
        rawId: String,
        mediaKind: ContentMediaKind,
        page: Int,
        limit: Int
    ): List<MetaReview> =
        deps?.metadataSecondaryRepository?.fetchKitsuReviews(
            rawId = rawId,
            mediaKind = mediaKind,
            page = page,
            limit = limit
        )?.reviews.orEmpty()

    suspend fun findPersonIdByExactName(name: String): Int? =
        deps?.metadataSecondaryRepository?.findPersonIdByExactName(name)

    suspend fun findCompanyIdByExactName(name: String): Int? =
        deps?.metadataSecondaryRepository?.findCompanyIdByExactName(name)

    companion object {
        fun noOp(): DetailSecondaryDisplayRepository =
            DetailSecondaryDisplayRepository(deps = null)
    }
}
