package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.DebridApiShapes
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridInstantAvailabilityFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridMagnetDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridMediaInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUnrestrictLinkDto
import com.nexio.tv.data.repository.RealDebridAuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val realDebridApi: RealDebridApi,
    private val realDebridAuthService: RealDebridAuthService
) {
    private suspend fun accountScope() = IntegrationScope.ProviderConfig(
        realDebridAuthService.getCurrentAuthState().username ?: "realdebrid"
    )

    suspend fun fetchTorrents(): List<RealDebridTorrentDto> =
        cachedRequest(
            cacheKey = "realdebrid:library:torrents",
            apiShapeId = DebridApiShapes.REAL_DEBRID_TORRENTS,
            operationKey = "real_debrid.torrents",
            codec = gsonCodec<List<RealDebridTorrentDto>>(),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) { authorization ->
            realDebridApi.getTorrents(authorization = authorization)
        }.orEmpty()

    suspend fun fetchDownloads(): List<RealDebridDownloadDto> =
        cachedRequest(
            cacheKey = "realdebrid:library:downloads",
            apiShapeId = DebridApiShapes.REAL_DEBRID_DOWNLOADS,
            operationKey = "real_debrid.downloads",
            codec = gsonCodec<List<RealDebridDownloadDto>>(),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) { authorization ->
            realDebridApi.getDownloads(authorization = authorization)
        }.orEmpty()

    suspend fun fetchTorrentInfo(id: String): RealDebridTorrentInfoDto? =
        cachedRequest(
            cacheKey = "realdebrid:torrent:$id:info",
            apiShapeId = DebridApiShapes.REAL_DEBRID_TORRENT_INFO,
            operationKey = "real_debrid.torrent_info",
            codec = gsonCodec<RealDebridTorrentInfoDto>(),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) { authorization ->
            realDebridApi.getTorrentInfo(authorization = authorization, id = id)
        }

    suspend fun fetchInstantAvailability(hash: String): Map<String, Map<String, List<Map<String, RealDebridInstantAvailabilityFileDto>>>>? =
        cachedRequest(
            cacheKey = "realdebrid:availability:$hash",
            apiShapeId = DebridApiShapes.REAL_DEBRID_INSTANT_AVAILABILITY,
            operationKey = "real_debrid.instant_availability",
            codec = gsonCodec<Map<String, Map<String, List<Map<String, RealDebridInstantAvailabilityFileDto>>>>>(),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) { authorization ->
            realDebridApi.getInstantAvailability(authorization = authorization, hash = hash)
        }

    suspend fun addMagnet(magnet: String): RealDebridMagnetDto? =
        mutationRequest(
            apiShapeId = DebridApiShapes.REAL_DEBRID_ADD_MAGNET,
            operationKey = "real_debrid.add_magnet",
            codec = gsonCodec<RealDebridMagnetDto>(),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) { authorization ->
            realDebridApi.addMagnet(authorization = authorization, magnet = magnet)
        }

    suspend fun selectFiles(id: String, files: String): Boolean =
        mutationRequest(
            apiShapeId = DebridApiShapes.REAL_DEBRID_SELECT_FILES,
            operationKey = "real_debrid.select_files",
            codec = gsonCodec<Boolean>(),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            load = {
                val response = realDebridAuthService.executeAuthOwnerRequest { authorization ->
                    realDebridApi.selectFiles(authorization = authorization, id = id, files = files)
                } ?: return@mutationRequest IntegrationLoadResult.HttpError(401, reason = "realdebrid_auth_missing")
                if (!response.isSuccessful) {
                    return@mutationRequest IntegrationLoadResult.HttpError(
                        response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "realdebrid_select_failed"
                    )
                }
                IntegrationLoadResult.Success(true)
            }
        ) ?: false

    suspend fun unrestrictLink(link: String): RealDebridUnrestrictLinkDto? =
        mutationRequest(
            apiShapeId = DebridApiShapes.REAL_DEBRID_UNRESTRICT_LINK,
            operationKey = "real_debrid.unrestrict_link",
            codec = gsonCodec<RealDebridUnrestrictLinkDto>(),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) { authorization ->
            realDebridApi.unrestrictLink(authorization = authorization, link = link)
        }

    suspend fun fetchMediaInfos(id: String): RealDebridMediaInfoDto? =
        cachedRequest(
            cacheKey = "realdebrid:mediainfo:$id",
            apiShapeId = DebridApiShapes.REAL_DEBRID_MEDIA_INFOS,
            operationKey = "real_debrid.media_infos",
            codec = gsonCodec<RealDebridMediaInfoDto>(),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) { authorization ->
            realDebridApi.getMediaInfos(authorization = authorization, id = id)
        }

    suspend fun deleteTorrent(id: String): Boolean =
        mutationRequest(
            apiShapeId = DebridApiShapes.REAL_DEBRID_DELETE_TORRENT,
            operationKey = "real_debrid.delete_torrent",
            codec = gsonCodec<Boolean>(),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = realDebridAuthService.executeAuthOwnerRequest { authorization ->
                    realDebridApi.deleteTorrent(authorization = authorization, id = id)
                } ?: return@mutationRequest IntegrationLoadResult.HttpError(401, reason = "realdebrid_auth_missing")
                if (!response.isSuccessful) {
                    return@mutationRequest IntegrationLoadResult.HttpError(
                        response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "realdebrid_delete_failed"
                    )
                }
                IntegrationLoadResult.Success(true)
            }
        ) ?: false

    private suspend fun <T> cachedRequest(
        cacheKey: String,
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        workClass: IntegrationWorkClass,
        block: suspend (String) -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.REAL_DEBRID,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = cacheKey,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 5L * 60L * 1000L
            ),
            workClass = workClass,
            scope = accountScope(),
            load = {
                val response = realDebridAuthService.executeAuthOwnerRequest(block)
                    ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "realdebrid_auth_missing")
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "realdebrid_request_failed"
                    )
                }
                IntegrationLoadResult.Success(body)
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    private suspend fun <T> mutationRequest(
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        workClass: IntegrationWorkClass,
        block: suspend (String) -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.REAL_DEBRID,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = null,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Mutation,
            workClass = workClass,
            scope = accountScope(),
            load = {
                val response = realDebridAuthService.executeAuthOwnerRequest(block)
                    ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "realdebrid_auth_missing")
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    return@IntegrationSpec IntegrationLoadResult.HttpError(
                        statusCode = response.code(),
                        retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                        reason = "realdebrid_mutation_failed"
                    )
                }
                IntegrationLoadResult.Success(body)
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    private suspend fun <T> mutationRequest(
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        workClass: IntegrationWorkClass,
        load: suspend () -> IntegrationLoadResult<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.REAL_DEBRID,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = null,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Mutation,
            workClass = workClass,
            scope = accountScope(),
            load = load
        )
        return runtime.get(spec).valueOrNull()
    }
}
