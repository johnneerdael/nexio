package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibraryMutationExecutor @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider
) {
    suspend fun createUserList(
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktIntegrationProvider.createUserList(id = id, body = body)
    }

    suspend fun createUserList(
        session: TrackingAuthSession,
        id: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktIntegrationProvider.createUserList(session = session, id = id, body = body)
    }

    suspend fun updateUserList(
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktIntegrationProvider.updateUserList(id = id, listId = listId, body = body)
    }

    suspend fun updateUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>? {
        return traktIntegrationProvider.updateUserList(session = session, id = id, listId = listId, body = body)
    }

    suspend fun deleteUserList(
        id: String,
        listId: String
    ): Response<Unit>? {
        return traktIntegrationProvider.deleteUserList(id = id, listId = listId)
    }

    suspend fun deleteUserList(
        session: TrackingAuthSession,
        id: String,
        listId: String
    ): Response<Unit>? {
        return traktIntegrationProvider.deleteUserList(session = session, id = id, listId = listId)
    }

    suspend fun reorderUserLists(
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? {
        return traktIntegrationProvider.reorderUserLists(id = id, body = body)
    }

    suspend fun reorderUserLists(
        session: TrackingAuthSession,
        id: String,
        body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>? {
        return traktIntegrationProvider.reorderUserLists(session = session, id = id, body = body)
    }

    suspend fun addToWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.addToWatchlist(body)
    }

    suspend fun addToWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.addToWatchlist(session, body)
    }

    suspend fun removeFromWatchlist(
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.removeFromWatchlist(body)
    }

    suspend fun removeFromWatchlist(
        session: TrackingAuthSession,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.removeFromWatchlist(session, body)
    }

    suspend fun addUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.addUserListItems(id = id, listId = listId, body = body)
    }

    suspend fun addUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.addUserListItems(session = session, id = id, listId = listId, body = body)
    }

    suspend fun removeUserListItems(
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.removeUserListItems(id = id, listId = listId, body = body)
    }

    suspend fun removeUserListItems(
        session: TrackingAuthSession,
        id: String,
        listId: String,
        body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>? {
        return traktIntegrationProvider.removeUserListItems(session = session, id = id, listId = listId, body = body)
    }
}
