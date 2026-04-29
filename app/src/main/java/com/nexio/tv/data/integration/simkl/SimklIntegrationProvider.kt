package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderAccountRef
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
import com.nexio.tv.domain.model.TrackingProvider
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
        val ownerSession = simklAuthService.accountScopedSession(session ?: simklAuthService.currentAuthSession())
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = SimklApiShapes.LAST_ACTIVITIES,
                operationKey = accountOperationKey(ownerSession, "simkl.last_activities"),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(ownerSession),
                profileContext = profileContext(ownerSession),
                call = {
                    val response = simklAuthService.executeAuthOwnerRequest(ownerSession) { authorization ->
                        simklApi.getLastActivities(authorization)
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
            apiShapeId = SimklApiShapes.PLAYBACK,
            rawOperationKey = "simkl.playback"
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
            rawOperationKey = "simkl.playback.delete"
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
            rawOperationKey = "simkl.list.add"
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
            rawOperationKey = "simkl.history.add"
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
            rawOperationKey = "simkl.history.remove"
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
            apiShapeId = SimklApiShapes.SCROBBLE,
            rawOperationKey = "simkl.scrobble.start"
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
            apiShapeId = SimklApiShapes.SCROBBLE,
            rawOperationKey = "simkl.scrobble.pause"
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
            apiShapeId = SimklApiShapes.SCROBBLE,
            rawOperationKey = "simkl.scrobble.stop"
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
            apiShapeId = SimklApiShapes.SCROBBLE,
            rawOperationKey = "simkl.checkin"
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
            rawOperationKey = operationKey,
            call = call
        )
    }

    private suspend fun <T> authorizedRead(
        session: TrackingAuthSession?,
        apiShapeId: String,
        rawOperationKey: String,
        call: suspend (authorization: String) -> retrofit2.Response<T>
    ): retrofit2.Response<T>? {
        val ownerSession = simklAuthService.accountScopedSession(session ?: simklAuthService.currentAuthSession())
        val operationKey = accountOperationKey(ownerSession, rawOperationKey)
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(ownerSession),
                profileContext = profileContext(ownerSession),
                call = {
                    val response = simklAuthService.executeAuthOwnerRequest(
                        session = ownerSession,
                        call = call
                    ) ?: return@IntegrationCallSpec IntegrationCallResult.Missing
                    IntegrationCallResult.Success(response)
                }
            )
        )
        return (result as? IntegrationCallResult.Success)?.value
    }

    private suspend fun <T> authorizedWrite(
        session: TrackingAuthSession?,
        apiShapeId: String = SimklApiShapes.LIBRARY_WRITE,
        rawOperationKey: String,
        call: suspend (authorization: String) -> retrofit2.Response<T>
    ): retrofit2.Response<T>? {
        val ownerSession = simklAuthService.accountScopedSession(session ?: simklAuthService.currentAuthSession())
        val operationKey = accountOperationKey(ownerSession, rawOperationKey)
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(ownerSession),
                profileContext = profileContext(ownerSession),
                call = {
                    val response = simklAuthService.executeAuthorizedWriteRequest(
                        session = ownerSession,
                        call = call
                    ) ?: return@IntegrationCallSpec IntegrationCallResult.Missing
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

    private fun accountScope(session: TrackingAuthSession): IntegrationScope.Account =
        IntegrationScope.Account(
            profileId = session.profileId,
            provider = IntegrationProvider.SIMKL,
            credentialHash = credentialHash(session)
        )

    private fun profileContext(session: TrackingAuthSession): ProfileExecutionContext =
        ProfileExecutionContext(
            profileId = session.profileId,
            sessionId = "simkl:${session.profileId}",
            displayLanguage = "en",
            region = "global",
            accounts = mapOf(
                IntegrationProvider.SIMKL to ProviderAccountRef(
                    provider = IntegrationProvider.SIMKL,
                    credentialHash = credentialHash(session),
                    accountIdHash = session.accountIdHash
                )
            )
        )

    private fun accountOperationKey(session: TrackingAuthSession, operationKey: String): String =
        "profile:${session.profileId}:provider:SIMKL:credential:${credentialHash(session)}:operation:$operationKey"

    private fun credentialHash(session: TrackingAuthSession): String {
        require(session.provider == TrackingProvider.SIMKL) { "Expected SIMKL session, got ${session.provider}" }
        return session.credentialHash?.takeIf { it.isNotBlank() } ?: "simkl:profile:${session.profileId}"
    }
}
