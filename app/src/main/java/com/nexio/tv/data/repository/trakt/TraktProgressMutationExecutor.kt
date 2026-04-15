package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktProgressMutationExecutor @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    suspend fun addHistory(
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addHistory(authHeader, body)
        }
    }

    suspend fun addHistory(
        session: TrackingAuthSession,
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.addHistory(authHeader, body)
        }
    }

    suspend fun removeHistory(
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.removeHistory(authHeader, body)
        }
    }

    suspend fun removeHistory(
        session: TrackingAuthSession,
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.removeHistory(authHeader, body)
        }
    }

    suspend fun deletePlayback(
        playbackId: Long
    ): Response<Unit>? {
        return traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.deletePlayback(authHeader, playbackId)
        }
    }

    suspend fun deletePlayback(
        session: TrackingAuthSession,
        playbackId: Long
    ): Response<Unit>? {
        return traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.deletePlayback(authHeader, playbackId)
        }
    }
}
