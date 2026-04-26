package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.TraktApiShapes
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCalendarEpisodeItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCommentItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktPopularListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowProgressResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserSettingsResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserStatsResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

data class TraktCommentsPage(
    val items: List<TraktCommentItemDto>,
    val hasMore: Boolean
)

data class TraktPagedResponse<T>(
    val body: T,
    val pageCount: Int?
)

@Singleton
class TraktIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    fun currentTraktProfileId(): Int = traktAuthService.currentTraktProfileId()

    fun isCircuitClosed(): Boolean = traktAuthService.isCircuitClosed()

    suspend fun requestDeviceCode(
        session: TrackingAuthSession,
        body: TraktDeviceCodeRequestDto
    ): Response<TraktDeviceCodeResponseDto>? =
        executeRawResponseCall(
            apiShapeId = "trakt.device_code",
            operationKey = "trakt.device_code.request",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(session.profileId)
        ) {
            traktApi.requestDeviceCode(body)
        }

    suspend fun requestDeviceToken(
        session: TrackingAuthSession,
        body: TraktDeviceTokenRequestDto
    ): Response<TraktTokenResponseDto>? =
        executeRawResponseCall(
            apiShapeId = "trakt.device_token",
            operationKey = "trakt.device_token.request",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(session.profileId)
        ) {
            traktApi.requestDeviceToken(body)
        }

    suspend fun refreshToken(
        session: TrackingAuthSession,
        body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>? =
        executeRawResponseCall(
            apiShapeId = "trakt.token_refresh",
            operationKey = "trakt.token.refresh",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(session.profileId)
        ) {
            traktApi.refreshToken(body)
        }

    suspend fun refreshTokenWithinRuntimeCall(
        session: TrackingAuthSession,
        body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>? =
        traktApi.refreshToken(body)

    suspend fun revokeToken(
        session: TrackingAuthSession,
        body: TraktRevokeRequestDto
    ): Response<Unit>? =
        executeRawResponseCall(
            apiShapeId = "trakt.token_revoke",
            operationKey = "trakt.token.revoke",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(session.profileId)
        ) {
            traktApi.revokeToken(body)
        }

    suspend fun getUserSettings(
        session: TrackingAuthSession
    ): Response<TraktUserSettingsResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.settings",
            operationKey = "trakt.user.settings",
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) { authorization ->
            traktApi.getUserSettings(authorization = authorization)
        }

    suspend fun getLastActivities(): IntegrationCallResult<TraktLastActivitiesResponseDto> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.last_activities",
            operationKey = "trakt.last_activities",
            request = { authorization -> traktApi.getLastActivities(authorization) },
            mapSuccess = { response -> response.body().toCallResult() }
        )

    suspend fun getUserStats(id: String): IntegrationCallResult<TraktUserStatsResponseDto> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.user.stats",
            operationKey = "trakt.user.stats",
            request = { authorization -> traktApi.getUserStats(authorization = authorization, id = id) },
            mapSuccess = { response -> response.body().toCallResult() }
        )

    suspend fun getWatched(
        type: String,
        extended: String? = null
    ): IntegrationCallResult<List<TraktWatchedMovieItemDto>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.watched",
            operationKey = "trakt.watched.$type",
            request = { authorization ->
                traktApi.getWatched(
                    authorization = authorization,
                    type = type,
                    extended = extended
                )
            },
            mapSuccess = { response -> IntegrationCallResult.Success(response.body().orEmpty()) }
        )

    suspend fun getWatchedShows(
        extended: String? = null
    ): IntegrationCallResult<List<TraktWatchedShowItemDto>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.watched.shows",
            operationKey = "trakt.watched.shows",
            request = { authorization ->
                traktApi.getWatchedShows(
                    authorization = authorization,
                    extended = extended
                )
            },
            mapSuccess = { response -> IntegrationCallResult.Success(response.body().orEmpty()) }
        )

    suspend fun getHiddenItems(
        section: String,
        type: String,
        page: Int,
        limit: Int
    ): IntegrationCallResult<TraktPagedResponse<List<TraktHiddenItemDto>>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.hidden_items",
            operationKey = "trakt.hidden_items.$section.$type",
            request = { authorization ->
                traktApi.getHiddenItems(
                    authorization = authorization,
                    section = section,
                    type = type,
                    page = page,
                    limit = limit
                )
            },
            mapSuccess = { response ->
                IntegrationCallResult.Success(
                    TraktPagedResponse(
                        body = response.body().orEmpty(),
                        pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
                    )
                )
            }
        )

    suspend fun getSeasonEpisodes(
        id: String,
        season: Int,
        extended: String? = null
    ): IntegrationCallResult<List<TraktEpisodeSummaryDto>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.season.episodes",
            operationKey = "trakt.season.episodes",
            request = { authorization ->
                traktApi.getSeasonEpisodes(
                    authorization = authorization,
                    id = id,
                    season = season,
                    extended = extended
                )
            },
            mapSuccess = { response -> IntegrationCallResult.Success(response.body().orEmpty()) }
        )

    suspend fun getEpisodeSummary(
        id: String,
        season: Int,
        episode: Int,
        extended: String? = null
    ): IntegrationCallResult<TraktEpisodeSummaryDto> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.episode.summary",
            operationKey = "trakt.episode.summary",
            request = { authorization ->
                traktApi.getEpisodeSummary(
                    authorization = authorization,
                    id = id,
                    season = season,
                    episode = episode,
                    extended = extended
                )
            },
            mapSuccess = { response -> response.body().toCallResult() }
        )

    suspend fun getShowProgressWatched(
        id: String,
        lastActivity: String? = null
    ): IntegrationCallResult<TraktShowProgressResponseDto> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.show.progress_watched",
            operationKey = "trakt.show.progress_watched",
            request = { authorization ->
                traktApi.getShowProgressWatched(
                    authorization = authorization,
                    id = id,
                    lastActivity = lastActivity
                )
            },
            mapSuccess = { response -> response.body().toCallResult() }
        )

    suspend fun getEpisodeHistory(
        page: Int,
        limit: Int
    ): IntegrationCallResult<TraktPagedResponse<List<TraktUserEpisodeHistoryItemDto>>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.episode.history",
            operationKey = "trakt.episode.history",
            request = { authorization ->
                traktApi.getEpisodeHistory(
                    authorization = authorization,
                    page = page,
                    limit = limit
                )
            },
            mapSuccess = { response ->
                IntegrationCallResult.Success(
                    TraktPagedResponse(
                        body = response.body().orEmpty(),
                        pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
                    )
                )
            }
        )

    suspend fun getPlayback(
        type: String,
        startAt: String? = null,
        endAt: String? = null
    ): IntegrationCallResult<List<TraktPlaybackItemDto>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = "trakt.playback",
            operationKey = "trakt.playback.$type",
            request = { authorization ->
                traktApi.getPlayback(
                    authorization = authorization,
                    type = type,
                    startAt = startAt,
                    endAt = endAt
                )
            },
            mapSuccess = { response -> IntegrationCallResult.Success(response.body().orEmpty()) }
        )

    suspend fun getWatchlist(
        session: TrackingAuthSession,
        type: String
    ): Response<List<TraktListItemDto>>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = if (type == "shows") TraktApiShapes.WATCHLIST_SHOWS else TraktApiShapes.WATCHLIST_MOVIES,
            operationKey = "trakt.watchlist.$type",
            workClass = IntegrationWorkClass.BACKGROUND_HYDRATION
        ) { authorization ->
            traktApi.getWatchlist(
                authorization = authorization,
                type = type
            )
        }

    suspend fun getUserLists(
        session: TrackingAuthSession,
        id: String
    ): Response<List<TraktListSummaryDto>>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.lists",
            operationKey = "trakt.user.lists",
            workClass = IntegrationWorkClass.BACKGROUND_HYDRATION
        ) { authorization ->
            traktApi.getUserLists(
                authorization = authorization,
                id = id
            )
        }

    suspend fun getUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        type: String
    ): Response<List<TraktListItemDto>>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_items",
            operationKey = "trakt.user.list_items.$type",
            workClass = IntegrationWorkClass.BACKGROUND_HYDRATION
        ) { authorization ->
            traktApi.getUserListItems(
                authorization = authorization,
                id = id,
                listId = listId,
                type = type
            )
        }

    suspend fun createUserList(
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.list_create",
            operationKey = "trakt.user.list_create",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.createUserList(authorization = authorization, id = id, body = body)
        }

    suspend fun createUserList(
        session: TrackingAuthSession,
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_create",
            operationKey = "trakt.user.list_create",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.createUserList(authorization = authorization, id = id, body = body)
        }

    suspend fun updateUserList(
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.list_update",
            operationKey = "trakt.user.list_update",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.updateUserList(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun updateUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_update",
            operationKey = "trakt.user.list_update",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.updateUserList(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun deleteUserList(
        id: String,
        listId: String
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.list_delete",
            operationKey = "trakt.user.list_delete",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.deleteUserList(authorization = authorization, id = id, listId = listId)
        }

    suspend fun deleteUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_delete",
            operationKey = "trakt.user.list_delete",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.deleteUserList(authorization = authorization, id = id, listId = listId)
        }

    suspend fun reorderUserLists(
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.lists_reorder",
            operationKey = "trakt.user.lists_reorder",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.reorderUserLists(authorization = authorization, id = id, body = body)
        }

    suspend fun reorderUserLists(
        session: TrackingAuthSession,
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.lists_reorder",
            operationKey = "trakt.user.lists_reorder",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.reorderUserLists(authorization = authorization, id = id, body = body)
        }

    suspend fun addToWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.watchlist.add",
            operationKey = "trakt.watchlist.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addToWatchlist(authorization = authorization, body = body)
        }

    suspend fun addToWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.watchlist.add",
            operationKey = "trakt.watchlist.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addToWatchlist(authorization = authorization, body = body)
        }

    suspend fun removeFromWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.watchlist.remove",
            operationKey = "trakt.watchlist.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeFromWatchlist(authorization = authorization, body = body)
        }

    suspend fun removeFromWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.watchlist.remove",
            operationKey = "trakt.watchlist.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeFromWatchlist(authorization = authorization, body = body)
        }

    suspend fun addUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.list_items.add",
            operationKey = "trakt.user.list_items.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addUserListItems(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun addUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_items.add",
            operationKey = "trakt.user.list_items.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addUserListItems(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun removeUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.user.list_items.remove",
            operationKey = "trakt.user.list_items.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeUserListItems(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun removeUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.user.list_items.remove",
            operationKey = "trakt.user.list_items.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeUserListItems(authorization = authorization, id = id, listId = listId, body = body)
        }

    suspend fun hideRecommendation(
        session: TrackingAuthSession,
        type: String,
        id: String
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.recommendation.hide",
            operationKey = "trakt.recommendation.hide.$type",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.hideRecommendation(
                authorization = authorization,
                type = type,
                id = id
            )
        }

    suspend fun addHistory(
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.history.add",
            operationKey = "trakt.history.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addHistory(authorization, body)
        }

    suspend fun addHistory(
        session: TrackingAuthSession,
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.history.add",
            operationKey = "trakt.history.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addHistory(authorization, body)
        }

    suspend fun removeHistory(
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.history.remove",
            operationKey = "trakt.history.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeHistory(authorization, body)
        }

    suspend fun removeHistory(
        session: TrackingAuthSession,
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.history.remove",
            operationKey = "trakt.history.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeHistory(authorization, body)
        }

    suspend fun deletePlayback(
        playbackId: Long
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            apiShapeId = "trakt.playback.delete",
            operationKey = "trakt.playback.delete",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.deletePlayback(authorization, playbackId)
        }

    suspend fun deletePlayback(
        session: TrackingAuthSession,
        playbackId: Long
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.playback.delete",
            operationKey = "trakt.playback.delete",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.deletePlayback(authorization, playbackId)
        }

    suspend fun checkin(
        session: TrackingAuthSession,
        body: TraktCheckinRequestDto
    ): Response<TraktCheckinResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.checkin",
            operationKey = "trakt.checkin",
            workClass = IntegrationWorkClass.SCROBBLE
        ) { authorization ->
            traktApi.checkin(authorization, body)
        }

    suspend fun scrobble(
        session: TrackingAuthSession,
        action: String,
        body: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = "trakt.scrobble",
            operationKey = "trakt.scrobble.$action",
            workClass = IntegrationWorkClass.SCROBBLE
        ) { authorization ->
            when (action) {
                "start" -> traktApi.scrobbleStart(authorization, body)
                "pause" -> traktApi.scrobblePause(authorization, body)
                else -> traktApi.scrobbleStop(authorization, body)
            }
        }

    suspend fun fetchCalendarShows(
        startDate: String,
        days: Int
    ): List<TraktCalendarEpisodeItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.CALENDAR_SHOWS,
            operationKey = "trakt.calendar.shows",
            cacheKey = profileCacheKey(profileId, "trakt:calendar:shows:start:$startDate:days:$days"),
            codec = gsonCodec<List<TraktCalendarEpisodeItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getMyShowsCalendar(
                        authorization = authorization,
                        startDate = startDate,
                        days = days
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_calendar_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchTrendingMovies(limit: Int): List<TraktTrendingMovieItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.TRENDING_MOVIES,
            operationKey = "trakt.trending.movies",
            cacheKey = profileCacheKey(profileId, "trakt:trending:movies:limit:$limit"),
            codec = gsonCodec<List<TraktTrendingMovieItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getTrendingMovies(
                        authorization = authorization,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_trending_movies_failed"
                    )
                }

                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )

        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchTrendingShows(limit: Int): List<TraktTrendingShowItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.TRENDING_SHOWS,
            operationKey = "trakt.trending.shows",
            cacheKey = profileCacheKey(profileId, "trakt:trending:shows:limit:$limit"),
            codec = gsonCodec<List<TraktTrendingShowItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getTrendingShows(
                        authorization = authorization,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_trending_shows_failed"
                    )
                }

                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )

        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchPopularMovies(limit: Int): List<TraktMovieDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.POPULAR_MOVIES,
            operationKey = "trakt.popular.movies",
            cacheKey = profileCacheKey(profileId, "trakt:popular:movies:limit:$limit"),
            codec = gsonCodec<List<TraktMovieDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getPopularMovies(
                        authorization = authorization,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_popular_movies_failed"
                    )
                }

                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )

        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchPopularShows(limit: Int): List<TraktShowDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.POPULAR_SHOWS,
            operationKey = "trakt.popular.shows",
            cacheKey = profileCacheKey(profileId, "trakt:popular:shows:limit:$limit"),
            codec = gsonCodec<List<TraktShowDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getPopularShows(
                        authorization = authorization,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_popular_shows_failed"
                    )
                }

                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )

        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchRecommendations(
        type: String,
        limit: Int
    ): List<TraktRecommendationItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = if (type == "shows") TraktApiShapes.RECOMMENDED_SHOWS else TraktApiShapes.RECOMMENDED_MOVIES,
            operationKey = "trakt.recommendations.$type",
            cacheKey = profileCacheKey(profileId, "trakt:recommendations:$type:limit:$limit"),
            codec = gsonCodec<List<TraktRecommendationItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getRecommendations(
                        authorization = authorization,
                        type = type,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")

                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_recommendations_failed"
                    )
                }

                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )

        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchPopularLists(
        page: Int,
        limit: Int
    ): List<TraktPopularListItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "trakt.popular.lists",
            operationKey = "trakt.popular.lists",
            cacheKey = profileCacheKey(profileId, "trakt:popular:lists:page:$page:limit:$limit"),
            codec = gsonCodec<List<TraktPopularListItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getPopularLists(
                        authorization = authorization,
                        page = page,
                        limit = limit
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_popular_lists_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchUserLists(
        id: String
    ): List<TraktListSummaryDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "trakt.user.lists",
            operationKey = "trakt.user.lists",
            cacheKey = profileCacheKey(profileId, "trakt:user:lists:$id"),
            codec = gsonCodec<List<TraktListSummaryDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getUserLists(
                        authorization = authorization,
                        id = id
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_user_lists_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchUserListItems(
        id: String,
        listId: String,
        type: String
    ): List<TraktListItemDto>? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "trakt.user.list_items",
            operationKey = "trakt.user.list_items.$type",
            cacheKey = profileCacheKey(profileId, "trakt:user:list-items:$id:$listId:$type"),
            codec = gsonCodec<List<TraktListItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getUserListItems(
                        authorization = authorization,
                        id = id,
                        listId = listId,
                        type = type
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_user_list_items_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchCommentsPage(
        pathId: String,
        isShow: Boolean,
        page: Int,
        limit: Int
    ): TraktCommentsPage? =
        if (isShow) {
            fetchShowCommentsPage(pathId = pathId, page = page, limit = limit)
        } else {
            fetchMovieCommentsPage(pathId = pathId, page = page, limit = limit)
        }

    suspend fun fetchMovieCommentsPage(
        pathId: String,
        page: Int,
        limit: Int
    ): TraktCommentsPage? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.MOVIE_COMMENTS,
            operationKey = "trakt.movie.comments",
            cacheKey = profileCacheKey(profileId, "trakt:reviews:movie:$pathId:page:$page:limit:$limit"),
            codec = gsonCodec<TraktCommentsPage>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getMovieComments(
                        authorization = authorization,
                        id = pathId,
                        page = page,
                        limit = limit
                    )
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

    suspend fun fetchShowCommentsPage(
        pathId: String,
        page: Int,
        limit: Int
    ): TraktCommentsPage? {
        val session = traktAuthService.currentAuthSession()
        val profileId = session.profileId
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.SHOW_COMMENTS,
            operationKey = "trakt.show.comments",
            cacheKey = profileCacheKey(profileId, "trakt:reviews:show:$pathId:page:$page:limit:$limit"),
            codec = gsonCodec<TraktCommentsPage>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(profileId),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getShowComments(
                        authorization = authorization,
                        id = pathId,
                        page = page,
                        limit = limit
                    )
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

    private suspend fun <ResponseBody, ResultBody> executeAuthorizedBackgroundCall(
        apiShapeId: String,
        operationKey: String,
        request: suspend (String) -> Response<ResponseBody>,
        mapSuccess: (Response<ResponseBody>) -> IntegrationCallResult<ResultBody>
    ): IntegrationCallResult<ResultBody> {
        val session = traktAuthService.currentAuthSession()
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                scope = IntegrationScope.Profile(session.profileId)
            ) {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    request(authorization)
                } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                if (!response.isSuccessful) {
                    return@IntegrationCallSpec IntegrationCallResult.HttpError(response.code())
                }
                mapSuccess(response)
            }
        )
    }

    private suspend fun <T> executeAuthorizedResponseCall(
        apiShapeId: String,
        operationKey: String,
        workClass: IntegrationWorkClass,
        request: suspend (String) -> Response<T>
    ): Response<T>? =
        executeAuthorizedResponseCall(
            session = traktAuthService.currentAuthSession(),
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            workClass = workClass,
            request = request
        )

    private suspend fun <T> executeAuthorizedResponseCall(
        session: TrackingAuthSession,
        apiShapeId: String,
        operationKey: String,
        workClass: IntegrationWorkClass,
        request: suspend (String) -> Response<T>
    ): Response<T>? =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = workClass,
                scope = IntegrationScope.Profile(session.profileId)
            ) {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    request(authorization)
                } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                IntegrationCallResult.Success(response)
            }
        ).valueOrNull()

    private suspend fun <T> executeRawResponseCall(
        apiShapeId: String,
        operationKey: String,
        workClass: IntegrationWorkClass,
        scope: IntegrationScope,
        request: suspend () -> Response<T>
    ): Response<T>? =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = workClass,
                scope = scope
            ) {
                IntegrationCallResult.Success(request())
            }
        ).valueOrNull()

    private fun <T : Any> T?.toCallResult(): IntegrationCallResult<T> =
        this?.let { IntegrationCallResult.Success(it) } ?: IntegrationCallResult.Missing

    private fun profileCacheKey(profileId: Int, logicalKey: String): String =
        "profile:$profileId:$logicalKey"
}
