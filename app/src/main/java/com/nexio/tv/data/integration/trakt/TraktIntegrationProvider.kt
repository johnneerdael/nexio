package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.TraktApiShapes
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderAccountRef
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
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

enum class TraktWatchedKind { MOVIES, SHOWS }

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
    private val traktAuthService: TraktAuthService,
    @Suppress("DEPRECATION")
    private val cacheStore: IntegrationCacheStore = IntegrationCacheStore.Noop
) {
    fun currentTraktProfileId(): Int = traktAuthService.currentTraktProfileId()

    fun isCircuitClosed(): Boolean = traktAuthService.isCircuitClosed()

    suspend fun requestDeviceCode(
        session: TrackingAuthSession,
        body: TraktDeviceCodeRequestDto
    ): Response<TraktDeviceCodeResponseDto>? =
        executeRawResponseCall(
            apiShapeId = TraktApiShapes.DEVICE_CODE,
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
            apiShapeId = TraktApiShapes.DEVICE_TOKEN,
            operationKey = "trakt.device_token.request",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Profile(session.profileId)
        ) {
            traktApi.requestDeviceToken(body)
        }

    suspend fun refreshToken(
        session: TrackingAuthSession,
        body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>? {
        val ownerSession = traktAuthService.accountScopedSession(session)
        return executeRawResponseCall(
            apiShapeId = TraktApiShapes.TOKEN_REFRESH,
            operationKey = accountOperationKey(ownerSession, "trakt.token.refresh"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(ownerSession),
            profileContext = profileContext(ownerSession)
        ) {
            traktApi.refreshToken(body)
        }
    }

    suspend fun refreshTokenWithinRuntimeCall(
        session: TrackingAuthSession,
        body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>? =
        traktApi.refreshToken(body)

    suspend fun revokeToken(
        session: TrackingAuthSession,
        body: TraktRevokeRequestDto
    ): Response<Unit>? {
        val ownerSession = traktAuthService.accountScopedSession(session)
        return executeRawResponseCall(
            apiShapeId = TraktApiShapes.TOKEN_REVOKE,
            operationKey = accountOperationKey(ownerSession, "trakt.token.revoke"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(ownerSession),
            profileContext = profileContext(ownerSession)
        ) {
            traktApi.revokeToken(body)
        }
    }

    suspend fun getUserSettings(
        session: TrackingAuthSession
    ): Response<TraktUserSettingsResponseDto>? =
        executeAuthorizedResponseCall(
            session = session,
            apiShapeId = TraktApiShapes.USER_SETTINGS,
            operationKey = "trakt.user.settings",
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) { authorization ->
            traktApi.getUserSettings(authorization = authorization)
        }

    suspend fun getLastActivities(): IntegrationCallResult<TraktLastActivitiesResponseDto> {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.LAST_ACTIVITIES,
            operationKey = accountOperationKey(session, "trakt.last_activities"),
            cacheKey = accountCacheKey(session, "trakt:sync:last_activities"),
            codec = gsonCodec<TraktLastActivitiesResponseDto>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = LAST_ACTIVITIES_TTL_MS,
                staleAfterExpiryMs = LAST_ACTIVITIES_STALE_GRACE_MS
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getLastActivities(authorization = authorization)
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_last_activities_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body() ?: TraktLastActivitiesResponseDto())
            }
        )
        val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
        return IntegrationCallResult.Success(value)
    }

    suspend fun getUserStats(id: String): IntegrationCallResult<TraktUserStatsResponseDto> =
        executeAuthorizedBackgroundCall(
            apiShapeId = TraktApiShapes.USER_STATS,
            operationKey = "trakt.user.stats",
            request = { authorization -> traktApi.getUserStats(authorization = authorization, id = id) },
            mapSuccess = { response -> response.body().toCallResult() }
        )

    suspend fun getWatched(
        type: String
    ): IntegrationCallResult<List<TraktWatchedMovieItemDto>> {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.WATCHED,
            operationKey = accountOperationKey(session, "trakt.watched.$type"),
            cacheKey = accountCacheKey(session, "trakt:sync:watched:$type"),
            codec = gsonCodec<List<TraktWatchedMovieItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = WATCHED_SNAPSHOT_TTL_MS,
                staleAfterExpiryMs = WATCHED_SNAPSHOT_STALE_GRACE_MS
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getWatched(
                        authorization = authorization,
                        type = type,
                        extended = null
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_watched_${type}_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
        return IntegrationCallResult.Success(value)
    }

    suspend fun getWatchedShows(): IntegrationCallResult<List<TraktWatchedShowItemDto>> {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.WATCHED_SHOWS,
            operationKey = accountOperationKey(session, "trakt.watched.shows"),
            cacheKey = accountCacheKey(session, "trakt:sync:watched:shows"),
            codec = gsonCodec<List<TraktWatchedShowItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = WATCHED_SNAPSHOT_TTL_MS,
                staleAfterExpiryMs = WATCHED_SNAPSHOT_STALE_GRACE_MS
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
            load = {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                    traktApi.getWatchedShows(
                        authorization = authorization,
                        extended = null
                    )
                } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
                if (!response.isSuccessful) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "trakt_watched_shows_failed"
                    )
                }
                IntegrationLoadResult.Success(response.body().orEmpty())
            }
        )
        val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
        return IntegrationCallResult.Success(value)
    }

    suspend fun invalidateWatchedSnapshot(kind: TraktWatchedKind) {
        val session = traktAuthService.accountScopedSession()
        val (apiShapeId, cacheKey, opSuffix) = when (kind) {
            TraktWatchedKind.MOVIES -> Triple(
                TraktApiShapes.WATCHED,
                accountCacheKey(session, "trakt:sync:watched:movies"),
                "movies"
            )
            TraktWatchedKind.SHOWS -> Triple(
                TraktApiShapes.WATCHED_SHOWS,
                accountCacheKey(session, "trakt:sync:watched:shows"),
                "shows"
            )
        }
        // Build a minimal spec; only the cacheKey is consulted by IntegrationCacheStore.delete(spec).
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = apiShapeId,
            operationKey = accountOperationKey(session, "trakt.watched.invalidate.$opSuffix"),
            cacheKey = cacheKey,
            codec = gsonCodec<Unit>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 1L, staleAfterExpiryMs = 0L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
            load = { IntegrationLoadResult.Success(Unit) }
        )
        cacheStore.delete(spec)
    }

    suspend fun invalidateLastActivities() {
        val session = traktAuthService.accountScopedSession()
        // Build a minimal spec; only the cacheKey is consulted by IntegrationCacheStore.delete(spec).
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.LAST_ACTIVITIES,
            operationKey = accountOperationKey(session, "trakt.last_activities.invalidate"),
            cacheKey = accountCacheKey(session, "trakt:sync:last_activities"),
            codec = gsonCodec<Unit>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 1L, staleAfterExpiryMs = 0L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
            load = { IntegrationLoadResult.Success(Unit) }
        )
        cacheStore.delete(spec)
    }

    suspend fun getHiddenItems(
        section: String,
        type: String,
        page: Int,
        limit: Int
    ): IntegrationCallResult<TraktPagedResponse<List<TraktHiddenItemDto>>> =
        executeAuthorizedBackgroundCall(
            apiShapeId = TraktApiShapes.HIDDEN_ITEMS,
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
            apiShapeId = TraktApiShapes.SEASON_EPISODES,
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
            apiShapeId = TraktApiShapes.EPISODE_SUMMARY,
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
            apiShapeId = TraktApiShapes.SHOW_PROGRESS_WATCHED,
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
            apiShapeId = TraktApiShapes.EPISODE_HISTORY,
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
            apiShapeId = TraktApiShapes.PLAYBACK,
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
            apiShapeId = TraktApiShapes.USER_LISTS,
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
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS,
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
            apiShapeId = TraktApiShapes.USER_LIST_CREATE,
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
            apiShapeId = TraktApiShapes.USER_LIST_CREATE,
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
            apiShapeId = TraktApiShapes.USER_LIST_UPDATE,
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
            apiShapeId = TraktApiShapes.USER_LIST_UPDATE,
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
            apiShapeId = TraktApiShapes.USER_LIST_DELETE,
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
            apiShapeId = TraktApiShapes.USER_LIST_DELETE,
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
            apiShapeId = TraktApiShapes.USER_LISTS_REORDER,
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
            apiShapeId = TraktApiShapes.USER_LISTS_REORDER,
            operationKey = "trakt.user.lists_reorder",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.reorderUserLists(authorization = authorization, id = id, body = body)
        }

    suspend fun addToWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = TraktApiShapes.WATCHLIST_ADD,
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
            apiShapeId = TraktApiShapes.WATCHLIST_ADD,
            operationKey = "trakt.watchlist.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addToWatchlist(authorization = authorization, body = body)
        }

    suspend fun removeFromWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = TraktApiShapes.WATCHLIST_REMOVE,
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
            apiShapeId = TraktApiShapes.WATCHLIST_REMOVE,
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
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS_ADD,
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
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS_ADD,
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
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS_REMOVE,
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
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS_REMOVE,
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
            apiShapeId = TraktApiShapes.RECOMMENDATION_HIDE,
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
            apiShapeId = TraktApiShapes.HISTORY_ADD,
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
            apiShapeId = TraktApiShapes.HISTORY_ADD,
            operationKey = "trakt.history.add",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.addHistory(authorization, body)
        }

    suspend fun removeHistory(
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? =
        executeAuthorizedResponseCall(
            apiShapeId = TraktApiShapes.HISTORY_REMOVE,
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
            apiShapeId = TraktApiShapes.HISTORY_REMOVE,
            operationKey = "trakt.history.remove",
            workClass = IntegrationWorkClass.MUTATION_OUTBOX
        ) { authorization ->
            traktApi.removeHistory(authorization, body)
        }

    suspend fun deletePlayback(
        playbackId: Long
    ): Response<Unit>? =
        executeAuthorizedResponseCall(
            apiShapeId = TraktApiShapes.PLAYBACK_DELETE,
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
            apiShapeId = TraktApiShapes.PLAYBACK_DELETE,
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
            apiShapeId = TraktApiShapes.CHECKIN,
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
            apiShapeId = TraktApiShapes.SCROBBLE,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.CALENDAR_SHOWS,
            operationKey = accountOperationKey(session, "trakt.calendar.shows"),
            cacheKey = globalContentCacheKey("trakt:calendar:shows:start:$startDate:days:$days"),
            codec = gsonCodec<List<TraktCalendarEpisodeItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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

    // F2-D-09: All global-content fns share IntegrationScope.GlobalContent, so a 429 from any
    // trending/popular/recommendations endpoint applies the backoff window to all of them — correct
    // for global data, sub-optimal for unrelated endpoints. Acceptable trade-off; revisit if
    // endpoint-level granularity becomes important.
    suspend fun fetchTrendingMovies(limit: Int): List<TraktTrendingMovieItemDto>? {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.TRENDING_MOVIES,
            operationKey = accountOperationKey(session, "trakt.trending.movies"),
            cacheKey = globalContentCacheKey("trakt:trending:movies:limit:$limit"),
            codec = gsonCodec<List<TraktTrendingMovieItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.TRENDING_SHOWS,
            operationKey = accountOperationKey(session, "trakt.trending.shows"),
            cacheKey = globalContentCacheKey("trakt:trending:shows:limit:$limit"),
            codec = gsonCodec<List<TraktTrendingShowItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.POPULAR_MOVIES,
            operationKey = accountOperationKey(session, "trakt.popular.movies"),
            cacheKey = globalContentCacheKey("trakt:popular:movies:limit:$limit"),
            codec = gsonCodec<List<TraktMovieDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.POPULAR_SHOWS,
            operationKey = accountOperationKey(session, "trakt.popular.shows"),
            cacheKey = globalContentCacheKey("trakt:popular:shows:limit:$limit"),
            codec = gsonCodec<List<TraktShowDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = if (type == "shows") TraktApiShapes.RECOMMENDED_SHOWS else TraktApiShapes.RECOMMENDED_MOVIES,
            operationKey = accountOperationKey(session, "trakt.recommendations.$type"),
            cacheKey = globalContentCacheKey("trakt:recommendations:$type:limit:$limit"),
            codec = gsonCodec<List<TraktRecommendationItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.POPULAR_LISTS,
            operationKey = accountOperationKey(session, "trakt.popular.lists"),
            cacheKey = globalContentCacheKey("trakt:popular:lists:page:$page:limit:$limit"),
            codec = gsonCodec<List<TraktPopularListItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
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

    /**
     * Uses accountCacheKey because each Trakt user owns their own list collection.
     * Two profiles on the same device have independent lists; their cache entries must not collide.
     */
    suspend fun fetchUserLists(
        id: String
    ): List<TraktListSummaryDto>? {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.USER_LISTS,
            operationKey = accountOperationKey(session, "trakt.user.lists"),
            cacheKey = accountCacheKey(session, "trakt:user:lists:$id"),
            codec = gsonCodec<List<TraktListSummaryDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
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

    /**
     * Uses accountCacheKey because list items belong to a specific user's custom list.
     * Different profiles have different lists and must each hold their own cache entry.
     */
    suspend fun fetchUserListItems(
        id: String,
        listId: String,
        type: String
    ): List<TraktListItemDto>? {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.USER_LIST_ITEMS,
            operationKey = accountOperationKey(session, "trakt.user.list_items.$type"),
            cacheKey = accountCacheKey(session, "trakt:user:list-items:$id:$listId:$type"),
            codec = gsonCodec<List<TraktListItemDto>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
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

    /**
     * Uses accountCacheKey because comment votes and user-specific state (hidden, liked) are
     * account-bound. Two profiles must not share a single cached comment list.
     */
    suspend fun fetchMovieCommentsPage(
        pathId: String,
        page: Int,
        limit: Int
    ): TraktCommentsPage? {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.MOVIE_COMMENTS,
            operationKey = accountOperationKey(session, "trakt.movie.comments"),
            cacheKey = accountCacheKey(session, "trakt:reviews:movie:$pathId:page:$page:limit:$limit"),
            codec = gsonCodec<TraktCommentsPage>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
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

    /**
     * Uses accountCacheKey because comment votes and user-specific state (hidden, liked) are
     * account-bound. Two profiles must not share a single cached comment list.
     */
    suspend fun fetchShowCommentsPage(
        pathId: String,
        page: Int,
        limit: Int
    ): TraktCommentsPage? {
        val session = traktAuthService.accountScopedSession()
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = TraktApiShapes.SHOW_COMMENTS,
            operationKey = accountOperationKey(session, "trakt.show.comments"),
            cacheKey = accountCacheKey(session, "trakt:reviews:show:$pathId:page:$page:limit:$limit"),
            codec = gsonCodec<TraktCommentsPage>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 10L * 60L * 1000L,
                staleAfterExpiryMs = 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = accountScope(session),
            profileContext = profileContext(session),
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
        val session = traktAuthService.accountScopedSession()
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = accountOperationKey(session, operationKey),
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                scope = accountScope(session),
                profileContext = profileContext(session)
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
            session = traktAuthService.accountScopedSession(),
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
    ): Response<T>? {
        val ownerSession = traktAuthService.accountScopedSession(session)
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = accountOperationKey(ownerSession, operationKey),
                workClass = workClass,
                scope = accountScope(ownerSession),
                profileContext = profileContext(ownerSession)
            ) {
                val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(ownerSession) { authorization ->
                    request(authorization)
                } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                IntegrationCallResult.Success(response)
            }
        ).valueOrNull()
    }

    private suspend fun <T> executeRawResponseCall(
        apiShapeId: String,
        operationKey: String,
        workClass: IntegrationWorkClass,
        scope: IntegrationScope,
        profileContext: ProfileExecutionContext? = null,
        request: suspend () -> Response<T>
    ): Response<T>? =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = workClass,
                scope = scope,
                profileContext = profileContext
            ) {
                IntegrationCallResult.Success(request())
            }
        ).valueOrNull()

    private fun <T : Any> T?.toCallResult(): IntegrationCallResult<T> =
        this?.let { IntegrationCallResult.Success(it) } ?: IntegrationCallResult.Missing

    private fun accountCacheKey(session: TrackingAuthSession, logicalKey: String): String =
        "profile:${session.profileId}:provider:TRAKT:credential:${credentialHash(session)}:$logicalKey"

    /**
     * F-C-06: cache key for global Trakt content (trending/popular/recommended/calendar) that
     * is NOT user-specific. Drops the profile: + credential: prefix so multiple profiles share
     * the same cache entry. Use `accountCacheKey(...)` for user-bound endpoints (watched/history/etc).
     */
    private fun globalContentCacheKey(logicalKey: String): String =
        "global:provider:TRAKT:$logicalKey"

    private fun accountScope(session: TrackingAuthSession): IntegrationScope.Account =
        IntegrationScope.Account(
            profileId = session.profileId,
            provider = IntegrationProvider.TRAKT,
            credentialHash = credentialHash(session)
        )

    private fun profileContext(session: TrackingAuthSession): ProfileExecutionContext =
        ProfileExecutionContext(
            profileId = session.profileId,
            sessionId = "trakt:${session.profileId}",
            displayLanguage = "en",
            region = "global",
            accounts = mapOf(
                IntegrationProvider.TRAKT to ProviderAccountRef(
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = credentialHash(session),
                    accountIdHash = session.accountIdHash
                )
            )
        )

    private fun accountOperationKey(session: TrackingAuthSession, operationKey: String): String =
        "profile:${session.profileId}:provider:TRAKT:credential:${credentialHash(session)}:operation:$operationKey"

    private fun credentialHash(session: TrackingAuthSession): String {
        require(session.provider == TrackingProvider.TRAKT) { "Expected TRAKT session, got ${session.provider}" }
        return session.credentialHash?.takeIf { it.isNotBlank() } ?: "trakt:profile:${session.profileId}"
    }

    private companion object {
        const val WATCHED_SNAPSHOT_TTL_MS: Long = 24L * 60L * 60L * 1000L              // 24h
        const val WATCHED_SNAPSHOT_STALE_GRACE_MS: Long = 7L * 24L * 60L * 60L * 1000L // 7d grace
        const val LAST_ACTIVITIES_TTL_MS: Long = 5L * 60L * 1000L                      // 5min
        const val LAST_ACTIVITIES_STALE_GRACE_MS: Long = 60L * 60L * 1000L             // 1h grace
    }
}
