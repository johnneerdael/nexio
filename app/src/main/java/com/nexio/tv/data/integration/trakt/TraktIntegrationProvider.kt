package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCommentItemDto
import com.nexio.tv.data.repository.TraktAuthService
import javax.inject.Inject
import javax.inject.Singleton

data class TraktCommentsPage(
    val items: List<TraktCommentItemDto>,
    val hasMore: Boolean
)

@Singleton
class TraktIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    suspend fun fetchCommentsPage(
        pathId: String,
        isShow: Boolean,
        page: Int,
        limit: Int
    ): TraktCommentsPage? {
        val endpoint = if (isShow) "show" else "movie"
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            cacheKey = "trakt:comments:$endpoint:$pathId:page:$page:limit:$limit",
            codec = gsonCodec<TraktCommentsPage>(),
            cachePolicy = IntegrationCachePolicy.ObserveOnly("task4-boundary"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(traktAuthService.currentTraktProfileId()),
            load = {
                val response = traktAuthService.executeAuthorizedRequest { authorization ->
                    if (isShow) {
                        traktApi.getShowComments(
                            authorization = authorization,
                            id = pathId,
                            page = page,
                            limit = limit
                        )
                    } else {
                        traktApi.getMovieComments(
                            authorization = authorization,
                            id = pathId,
                            page = page,
                            limit = limit
                        )
                    }
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_comments_failed"
                    )
                }

                val items = response.body().orEmpty()
                val totalItems = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull()
                val hasMore = if (totalItems != null) {
                    page * limit < totalItems
                } else {
                    items.size >= limit
                }

                IntegrationLoadResult.Success(TraktCommentsPage(items = items, hasMore = hasMore))
            }
        )

        return runtime.get(spec).valueOrNull()
    }
}
