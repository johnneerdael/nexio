package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import com.nexio.tv.domain.model.MetaReviewType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultReviewsRepository @Inject constructor(
    private val traktProvider: TraktIntegrationProvider
) : ReviewsRepository {
    override suspend fun fetchTraktReviewPage(
        pathId: String,
        isShow: Boolean,
        page: Int,
        limit: Int
    ): TraktReviewPage? {
        val payload = traktProvider.fetchCommentsPage(
            pathId = pathId,
            isShow = isShow,
            page = page,
            limit = limit
        ) ?: return null

        return TraktReviewPage(
            reviews = payload.items.mapNotNull { item ->
                val body = item.comment?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val author = item.user?.name?.trim()?.takeIf { it.isNotBlank() }
                    ?: item.user?.username?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Trakt user"
                val stableId = item.id?.toString() ?: body.hashCode().toString()

                MetaReview(
                    id = stableId,
                    author = author,
                    content = body,
                    rating = item.userStats?.rating,
                    createdAt = item.createdAt?.takeIf { it.isNotBlank() },
                    updatedAt = item.updatedAt?.takeIf { it.isNotBlank() },
                    source = MetaReviewSource.TRAKT,
                    type = if (item.review == true) MetaReviewType.REVIEW else MetaReviewType.SHOUT,
                    hasSpoiler = item.spoiler == true
                )
            },
            hasMore = payload.hasMore
        )
    }
}
