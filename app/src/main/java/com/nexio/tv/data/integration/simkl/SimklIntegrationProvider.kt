package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.SimklApiShapes
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.data.repository.toLibraryItemsResponse
import com.nexio.tv.data.repository.SimklAuthService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.integration.simkl.transport.SimklDiscoveryTransport
import com.nexio.tv.data.integration.simkl.transport.SimklDiscoveryTransportResult
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val simklApi: SimklApi,
    private val simklAuthService: SimklAuthService,
    private val moshi: Moshi,
    private val transport: SimklDiscoveryTransport
) {
    suspend fun getLastActivities(
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklLastActivitiesResponseDto>? {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = SimklApiShapes.LAST_ACTIVITIES,
                operationKey = "simkl.last_activities",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = if (session != null) {
                        simklAuthService.executeAuthOwnerRequest(session) { authorization ->
                            simklApi.getLastActivities(authorization)
                        }
                    } else {
                        simklAuthService.executeAuthOwnerRequest { authorization ->
                            simklApi.getLastActivities(authorization)
                        }
                    } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                    IntegrationCallResult.Success(response)
                }
            )
        )
        return (result as? IntegrationCallResult.Success)?.value
    }

    suspend fun getAllItemsByStatus(
        type: String,
        status: String,
        dateFrom: String? = null,
        extended: String? = null,
        episodeWatchedAt: String? = null,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<List<SimklLibraryItemDto>>? {
        return authorizedLibraryRead(
            session = session,
            operationKey = "simkl.library.by_status"
        ) { authorization ->
            simklApi.getAllItemsByStatusRaw(
                authorization = authorization,
                type = type,
                status = status,
                dateFrom = dateFrom,
                extended = extended,
                episodeWatchedAt = episodeWatchedAt
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getAllItemsByType(
        type: String,
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<List<SimklLibraryItemDto>>? {
        return authorizedLibraryRead(
            session = session,
            operationKey = "simkl.library.by_type"
        ) { authorization ->
            simklApi.getAllItemsByTypeRaw(
                authorization = authorization,
                type = type,
                dateFrom = dateFrom,
                extended = extended
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getAllItems(
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<List<SimklLibraryItemDto>>? {
        return authorizedLibraryRead(
            session = session,
            operationKey = "simkl.library.all"
        ) { authorization ->
            simklApi.getAllItemsRaw(
                authorization = authorization,
                dateFrom = dateFrom,
                extended = extended
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getPlayback(
        type: String,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<List<SimklPlaybackItemDto>>? {
        return authorizedRead(
            session = session,
            apiShapeId = "simkl.playback",
            operationKey = "simkl.playback"
        ) { authorization ->
            simklApi.getPlayback(authorization = authorization, type = type)
        }
    }

    suspend fun deletePlayback(
        playbackId: Long,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<Unit>? {
        return authorizedWrite(
            session = session,
            operationKey = "simkl.playback.delete"
        ) { authorization ->
            simklApi.deletePlayback(authorization = authorization, playbackId = playbackId)
        }
    }

    suspend fun addToList(
        body: SimklAddToListRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklAddToListResponseDto>? {
        return authorizedWrite(
            session = session,
            operationKey = "simkl.list.add"
        ) { authorization ->
            simklApi.addToList(authorization = authorization, body = body)
        }
    }

    suspend fun addHistory(
        body: SimklHistoryAddRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<Unit>? {
        return authorizedWrite(
            session = session,
            operationKey = "simkl.history.add"
        ) { authorization ->
            simklApi.addHistory(authorization = authorization, body = body)
        }
    }

    suspend fun removeFromHistoryAndLists(
        body: SimklHistoryRemoveRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<Unit>? {
        return authorizedWrite(
            session = session,
            operationKey = "simkl.history.remove"
        ) { authorization ->
            simklApi.removeFromHistoryAndLists(authorization = authorization, body = body)
        }
    }

    suspend fun scrobbleStart(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklScrobbleResponseDto>? {
        return authorizedWrite(
            session = session,
            apiShapeId = "simkl.scrobble",
            operationKey = "simkl.scrobble.start"
        ) { authorization ->
            simklApi.scrobbleStart(authorization = authorization, body = body)
        }
    }

    suspend fun scrobblePause(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklScrobbleResponseDto>? {
        return authorizedWrite(
            session = session,
            apiShapeId = "simkl.scrobble",
            operationKey = "simkl.scrobble.pause"
        ) { authorization ->
            simklApi.scrobblePause(authorization = authorization, body = body)
        }
    }

    suspend fun scrobbleStop(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklScrobbleResponseDto>? {
        return authorizedWrite(
            session = session,
            apiShapeId = "simkl.scrobble",
            operationKey = "simkl.scrobble.stop"
        ) { authorization ->
            simklApi.scrobbleStop(authorization = authorization, body = body)
        }
    }

    suspend fun checkin(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): retrofit2.Response<SimklScrobbleResponseDto>? {
        return authorizedWrite(
            session = session,
            apiShapeId = "simkl.scrobble",
            operationKey = "simkl.checkin"
        ) { authorization ->
            simklApi.checkin(authorization = authorization, body = body)
        }
    }

    suspend fun fetchDiscoveryBody(url: String): String? {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = SimklApiShapes.DISCOVERY,
                operationKey = "simkl.discovery.fetch_body",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    when (val transportResult = transport.fetchDiscoveryBody(url)) {
                        is SimklDiscoveryTransportResult.Success -> IntegrationCallResult.Success(
                            transportResult.body
                        )
                        SimklDiscoveryTransportResult.Missing -> IntegrationCallResult.Missing
                        is SimklDiscoveryTransportResult.NetworkError -> IntegrationCallResult.NetworkError(
                            transportResult.exception
                        )
                        is SimklDiscoveryTransportResult.HttpError -> IntegrationCallResult.HttpError(
                            transportResult.statusCode
                        )
                    }
                }
            )
        )
        return (result as? IntegrationCallResult.Success)?.value
    }

    private suspend fun authorizedLibraryRead(
        session: TrackingAuthSession?,
        operationKey: String,
        call: suspend (authorization: String) -> retrofit2.Response<List<SimklLibraryItemDto>>
    ): retrofit2.Response<List<SimklLibraryItemDto>>? {
        return authorizedRead(
            session = session,
            apiShapeId = SimklApiShapes.LIBRARY_READ,
            operationKey = operationKey,
            call = call
        )
    }

    private suspend fun <T> authorizedRead(
        session: TrackingAuthSession?,
        apiShapeId: String,
        operationKey: String,
        call: suspend (authorization: String) -> retrofit2.Response<T>
    ): retrofit2.Response<T>? {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = if (session != null) {
                        simklAuthService.executeAuthOwnerRequest(session, call)
                    } else {
                        simklAuthService.executeAuthOwnerRequest(call)
                    } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                    IntegrationCallResult.Success(response)
                }
            )
        )
        return (result as? IntegrationCallResult.Success)?.value
    }

    private suspend fun <T> authorizedWrite(
        session: TrackingAuthSession?,
        apiShapeId: String = SimklApiShapes.LIBRARY_WRITE,
        operationKey: String,
        call: suspend (authorization: String) -> retrofit2.Response<T>
    ): retrofit2.Response<T>? {
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = if (session != null) {
                        simklAuthService.executeAuthorizedWriteRequest(session, call)
                    } else {
                        simklAuthService.executeAuthorizedWriteRequest(call)
                    } ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                    if (response.isSuccessful) {
                        IntegrationCallResult.Success(response)
                    } else {
                        IntegrationCallResult.Success(response)
                    }
                }
            )
        )
        return (result as? IntegrationCallResult.Success)?.value
    }
}
