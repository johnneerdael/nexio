package com.nexio.tv.data.repository

import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.Response

@Singleton
class SimklTrackingRemoteDataSource @Inject constructor(
    private val simklApi: SimklApi,
    private val simklAuthDataStore: SimklAuthDataStore
) {
    suspend fun getLastActivities(): Response<SimklLastActivitiesResponseDto> {
        return simklApi.getLastActivities(authorizationHeader())
    }

    suspend fun getAllItemsByStatus(
        type: String,
        status: String,
        dateFrom: String? = null,
        extended: String? = null,
        episodeWatchedAt: String? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklApi.getAllItemsByStatus(
            authorization = authorizationHeader(),
            type = type,
            status = status,
            dateFrom = dateFrom,
            extended = extended,
            episodeWatchedAt = episodeWatchedAt
        )
    }

    suspend fun getAllItemsByType(
        type: String,
        dateFrom: String? = null,
        extended: String? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklApi.getAllItemsByType(
            authorization = authorizationHeader(),
            type = type,
            dateFrom = dateFrom,
            extended = extended
        )
    }

    suspend fun getAllItems(
        dateFrom: String? = null,
        extended: String? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklApi.getAllItems(
            authorization = authorizationHeader(),
            dateFrom = dateFrom,
            extended = extended
        )
    }

    suspend fun getPlayback(type: String): Response<List<SimklPlaybackItemDto>> {
        return simklApi.getPlayback(authorization = authorizationHeader(), type = type)
    }

    suspend fun deletePlayback(playbackId: Long): Response<Unit> {
        return simklApi.deletePlayback(authorization = authorizationHeader(), playbackId = playbackId)
    }

    suspend fun addToList(body: SimklAddToListRequestDto): Response<SimklAddToListResponseDto> {
        return simklApi.addToList(authorization = authorizationHeader(), body = body)
    }

    suspend fun addHistory(body: SimklHistoryAddRequestDto): Response<Unit> {
        return simklApi.addHistory(authorization = authorizationHeader(), body = body)
    }

    suspend fun removeFromHistoryAndLists(body: SimklHistoryRemoveRequestDto): Response<Unit> {
        return simklApi.removeFromHistoryAndLists(authorization = authorizationHeader(), body = body)
    }

    suspend fun scrobbleStart(body: SimklScrobbleRequestDto): Response<SimklScrobbleResponseDto> {
        return simklApi.scrobbleStart(authorization = authorizationHeader(), body = body)
    }

    suspend fun scrobblePause(body: SimklScrobbleRequestDto): Response<SimklScrobbleResponseDto> {
        return simklApi.scrobblePause(authorization = authorizationHeader(), body = body)
    }

    suspend fun scrobbleStop(body: SimklScrobbleRequestDto): Response<SimklScrobbleResponseDto> {
        return simklApi.scrobbleStop(authorization = authorizationHeader(), body = body)
    }

    suspend fun checkin(body: SimklScrobbleRequestDto): Response<SimklScrobbleResponseDto> {
        return simklApi.checkin(authorization = authorizationHeader(), body = body)
    }

    private suspend fun authorizationHeader(): String {
        val token = simklAuthDataStore.state.first().accessToken?.trim().orEmpty()
        require(token.isNotBlank()) { "SIMKL authentication required" }
        return "Bearer $token"
    }
}
