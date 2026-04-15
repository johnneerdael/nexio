package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibraryMutationExecutor @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    suspend fun createUserList(
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.createUserList(authorization = authHeader, id = id, body = body)
        }
    }

    suspend fun createUserList(
        session: TrackingAuthSession,
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.createUserList(authorization = authHeader, id = id, body = body)
        }
    }

    suspend fun updateUserList(
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.updateUserList(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }

    suspend fun updateUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.updateUserList(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }

    suspend fun deleteUserList(
        id: String,
        listId: String
    ): Response<Unit>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.deleteUserList(authorization = authHeader, id = id, listId = listId)
        }
    }

    suspend fun deleteUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String
    ): Response<Unit>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.deleteUserList(authorization = authHeader, id = id, listId = listId)
        }
    }

    suspend fun reorderUserLists(
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.reorderUserLists(authorization = authHeader, id = id, body = body)
        }
    }

    suspend fun reorderUserLists(
        session: TrackingAuthSession,
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.reorderUserLists(authorization = authHeader, id = id, body = body)
        }
    }

    suspend fun addToWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addToWatchlist(authorization = authHeader, body = body)
        }
    }

    suspend fun addToWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.addToWatchlist(authorization = authHeader, body = body)
        }
    }

    suspend fun removeFromWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.removeFromWatchlist(authorization = authHeader, body = body)
        }
    }

    suspend fun removeFromWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.removeFromWatchlist(authorization = authHeader, body = body)
        }
    }

    suspend fun addUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addUserListItems(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }

    suspend fun addUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.addUserListItems(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }

    suspend fun removeUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.removeUserListItems(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }

    suspend fun removeUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.removeUserListItems(authorization = authHeader, id = id, listId = listId, body = body)
        }
    }
}
