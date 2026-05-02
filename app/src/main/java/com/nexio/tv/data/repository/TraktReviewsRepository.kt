package com.nexio.tv.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktCommentItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCommentUserDto
import com.nexio.tv.data.remote.dto.trakt.TraktCommentUserStatsDto
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import com.nexio.tv.domain.model.MetaReviewType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktReviewsRepository @Inject constructor(
    private val traktProvider: TraktIntegrationProvider
) {
    private val gson = Gson()

    suspend fun fetchPage(
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
            reviews = (payload.items as List<*>).mapNotNull { raw ->
                val item = normalizeCommentItem(raw) ?: return@mapNotNull null
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

    private fun normalizeCommentItem(raw: Any?): TraktCommentItemDto? =
        when (raw) {
            null -> null
            is TraktCommentItemDto -> raw
            is Map<*, *> -> raw.toTraktCommentItemDto()
            else -> runCatching {
                val json = gson.toJson(raw)
                val mapType = object : TypeToken<Map<String, Any?>>() {}.type
                val decoded = gson.fromJson<Map<String, Any?>>(json, mapType)
                decoded.toTraktCommentItemDto()
            }.getOrNull()
        }

    private fun Map<*, *>.toTraktCommentItemDto(): TraktCommentItemDto =
        TraktCommentItemDto(
            id = longValue("id"),
            createdAt = stringValue("created_at") ?: stringValue("createdAt"),
            updatedAt = stringValue("updated_at") ?: stringValue("updatedAt"),
            comment = stringValue("comment"),
            review = booleanValue("review"),
            spoiler = booleanValue("spoiler"),
            user = mapValue("user")?.let { user ->
                TraktCommentUserDto(
                    username = user.stringValue("username"),
                    name = user.stringValue("name")
                )
            },
            userStats = (mapValue("user_stats") ?: mapValue("userStats"))?.let { stats ->
                TraktCommentUserStatsDto(rating = stats.doubleValue("rating"))
            }
        )

    private fun Map<*, *>.mapValue(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.stringValue(key: String): String? =
        (this[key] as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<*, *>.longValue(key: String): Long? =
        when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private fun Map<*, *>.doubleValue(key: String): Double? =
        when (val value = this[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

    private fun Map<*, *>.booleanValue(key: String): Boolean? =
        when (val value = this[key]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
}
