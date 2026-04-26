package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nexio.tv.data.repository.TrackingAuthSession
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktProgressMutationExecutor @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider
) {
    suspend fun addHistory(
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? {
        return traktIntegrationProvider.addHistory(body)
    }

    suspend fun addHistory(
        session: TrackingAuthSession,
        body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>? {
        return traktIntegrationProvider.addHistory(session, body)
    }

    suspend fun removeHistory(
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? {
        return traktIntegrationProvider.removeHistory(body)
    }

    suspend fun removeHistory(
        session: TrackingAuthSession,
        body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>? {
        return traktIntegrationProvider.removeHistory(session, body)
    }

    suspend fun deletePlayback(
        playbackId: Long
    ): Response<Unit>? {
        return traktIntegrationProvider.deletePlayback(playbackId)
    }

    suspend fun deletePlayback(
        session: TrackingAuthSession,
        playbackId: Long
    ): Response<Unit>? {
        return traktIntegrationProvider.deletePlayback(session, playbackId)
    }
}
