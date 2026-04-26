package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultReviewsRepository @Inject constructor(
    private val traktReviewsRepository: TraktReviewsRepository
) : ReviewsRepository {
    override suspend fun fetchTraktReviewPage(
        pathId: String,
        isShow: Boolean,
        page: Int,
        limit: Int
    ): TraktReviewPage? =
        traktReviewsRepository.fetchPage(
            pathId = pathId,
            isShow = isShow,
            page = page,
            limit = limit
        )
}
