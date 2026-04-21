package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.MetaReview

data class TraktReviewPage(
    val reviews: List<MetaReview>,
    val hasMore: Boolean
)

interface ReviewsRepository {
    suspend fun fetchTraktReviewPage(
        pathId: String,
        isShow: Boolean,
        page: Int,
        limit: Int
    ): TraktReviewPage?
}
