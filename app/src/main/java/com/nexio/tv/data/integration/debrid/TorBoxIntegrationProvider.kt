package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
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
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.dto.debrid.TorBoxCachedTorrentDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCheckCachedRequestDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCreateTorrentDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceCodeDataDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceTokenDataDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxDeviceTokenRequestDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxUserDto
import okhttp3.RequestBody
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorBoxIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val torBoxApi: TorBoxApi
) {
    suspend fun fetchAccountInfo(apiKey: String): IntegrationCallResult<TorBoxEnvelopeDto<TorBoxUserDto>> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TORBOX,
                apiShapeId = DebridApiShapes.TORBOX_ACCOUNT,
                operationKey = "torbox.account.fetch",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = accountScope(apiKey),
                call = {
                    val response = runCatching { torBoxApi.getCurrentUser(authorization = "Bearer $apiKey") }
                        .getOrElse {
                            if (it is CancellationException) throw it
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                        }
                    val body = response.body()
                    if (!response.isSuccessful || body == null || body.success == false) {
                        IntegrationCallResult.HttpError(
                            response.code(),
                            reason = body?.detail ?: body?.error ?: "Invalid TorBox API key"
                        )
                    } else {
                        IntegrationCallResult.Success(body)
                    }
                }
            )
        )

    suspend fun startDeviceCode(): IntegrationCallResult<TorBoxEnvelopeDto<TorBoxDeviceCodeDataDto>> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TORBOX,
                apiShapeId = "torbox.device.start",
                operationKey = "torbox.device.start",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("torbox:device-code"),
                call = {
                    val response = runCatching { torBoxApi.startDeviceCode() }
                        .getOrElse {
                            if (it is CancellationException) throw it
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                        }
                    val body = response.body()
                    if (!response.isSuccessful || body == null || body.success == false) {
                        IntegrationCallResult.HttpError(
                            response.code(),
                            reason = body?.detail ?: body?.error ?: "Failed to start TorBox device-code flow"
                        )
                    } else {
                        IntegrationCallResult.Success(body)
                    }
                }
            )
        )

    suspend fun pollDeviceToken(deviceCode: String): IntegrationCallResult<TorBoxEnvelopeDto<TorBoxDeviceTokenDataDto>> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TORBOX,
                apiShapeId = "torbox.device.token",
                operationKey = "torbox.device.token",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("torbox:device-code:${deviceCode.hashCode()}"),
                call = {
                    val response = runCatching {
                        torBoxApi.pollDeviceToken(TorBoxDeviceTokenRequestDto(deviceCode))
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                    }
                    val body = response.body()
                    if (response.isSuccessful && body != null && body.success != false) {
                        IntegrationCallResult.Success(body)
                    } else {
                        IntegrationCallResult.HttpError(
                            response.code(),
                            reason = body?.detail ?: body?.error
                        )
                    }
                }
            )
        )

    suspend fun fetchTorrentList(
        apiKey: String,
        id: Int? = null,
        limit: Int? = 100
    ): TorBoxEnvelopeDto<List<TorBoxTorrentListItemDto>>? =
        cachedRequest(
            cacheKey = "torbox:list:${id ?: "all"}:${limit ?: "default"}:${apiKey.hashCode()}",
            apiShapeId = DebridApiShapes.TORBOX_TORRENT_LIST,
            operationKey = "torbox.torrent_list",
            codec = gsonCodec<TorBoxEnvelopeDto<List<TorBoxTorrentListItemDto>>>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.USER_VISIBLE
        ) {
            torBoxApi.getMyTorrentList(
                authorization = "Bearer $apiKey",
                id = id,
                bypassCache = true,
                limit = limit
            )
        }

    suspend fun requestDownloadLink(apiKey: String, torrentId: Int, fileId: Int): String? =
        mutationRequest(
            apiShapeId = DebridApiShapes.TORBOX_DOWNLOAD_LINK,
            operationKey = "torbox.download_link",
            codec = gsonCodec<String>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            val response = torBoxApi.requestDownloadLink(
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false
            )
            val body = response.body()
            if (!response.isSuccessful || body?.success == false || body?.data.isNullOrBlank()) {
                IntegrationLoadResult.HttpError(response.code(), reason = "torbox_download_failed")
            } else {
                IntegrationLoadResult.Success(body!!.data!!)
            }
        }

    suspend fun checkCachedTorrents(
        apiKey: String,
        body: TorBoxCheckCachedRequestDto
    ): TorBoxEnvelopeDto<List<TorBoxCachedTorrentDto>>? =
        cachedRequest(
            cacheKey = "torbox:cached:${body.hashCode()}:${apiKey.hashCode()}",
            apiShapeId = DebridApiShapes.TORBOX_CHECK_CACHED,
            operationKey = "torbox.check_cached",
            codec = gsonCodec<TorBoxEnvelopeDto<List<TorBoxCachedTorrentDto>>>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION
        ) {
            torBoxApi.checkCachedTorrents(
                authorization = "Bearer $apiKey",
                body = body
            )
        }

    suspend fun createTorrent(
        apiKey: String,
        magnet: RequestBody,
        allowZip: RequestBody,
        addOnlyIfCached: RequestBody
    ): TorBoxEnvelopeDto<TorBoxCreateTorrentDto>? =
        mutationRequest(
            apiShapeId = DebridApiShapes.TORBOX_CREATE_TORRENT,
            operationKey = "torbox.create_torrent",
            codec = gsonCodec<TorBoxEnvelopeDto<TorBoxCreateTorrentDto>>(),
            scope = accountScope(apiKey),
            workClass = IntegrationWorkClass.PLAYBACK_RESOLUTION,
            load = loadFrom {
                torBoxApi.createTorrent(
                    authorization = "Bearer $apiKey",
                    magnet = magnet,
                    allowZip = allowZip,
                    addOnlyIfCached = addOnlyIfCached
                )
            }
        )

    private suspend fun <T> cachedRequest(
        cacheKey: String,
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        block: suspend () -> retrofit2.Response<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TORBOX,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = cacheKey,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 5L * 60L * 1000L
            ),
            workClass = workClass,
            scope = scope,
            load = loadFrom(block)
        )
        return runtime.get(spec).valueOrNull()
    }

    private suspend fun <T> mutationRequest(
        apiShapeId: String,
        operationKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        load: suspend () -> IntegrationLoadResult<T>
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TORBOX,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = null,
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Mutation,
            workClass = workClass,
            scope = scope,
            load = load
        )
        return runtime.get(spec).valueOrNull()
    }

    private fun accountScope(apiKey: String): IntegrationScope =
        IntegrationScope.ProviderConfig("torbox:${apiKey.hashCode()}")

    private fun <T> loadFrom(
        block: suspend () -> retrofit2.Response<T>
    ): suspend () -> IntegrationLoadResult<T> = {
        runCatching { block() }.fold(
            onSuccess = { response ->
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    IntegrationLoadResult.HttpError(response.code(), reason = "torbox_request_failed")
                } else {
                    IntegrationLoadResult.Success(body)
                }
            },
            onFailure = {
                if (it is CancellationException) throw it
                IntegrationLoadResult.NetworkError(it)
            }
        )
    }
}
