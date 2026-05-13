package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvApiShapes
import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookupResult
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCodec
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.valueOrNull
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

@Singleton
class FanartTvLookupShape @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val api: FanartTvApi
) : FanartTvLookup {

    override suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult {
        val spec = specFor(callId = callId, apiKey = apiKey) {
            runCatching {
                when (callId.type) {
                    FanartTvCallId.Type.MOVIE -> api.getMovie(tmdbId = callId.value, apiKey = apiKey)
                    FanartTvCallId.Type.TV -> api.getTv(tvdbId = callId.value, apiKey = apiKey)
                }
            }.fold(
                onSuccess = { document -> IntegrationLoadResult.Success(document) },
                onFailure = { error ->
                    when {
                        error is HttpException && error.code() == 404 ->
                            IntegrationLoadResult.HttpError(404, reason = "fanarttv_not_found")
                        error is HttpException && error.code() == 401 ->
                            IntegrationLoadResult.HttpError(401, reason = "fanarttv_auth_failed")
                        error is HttpException ->
                            IntegrationLoadResult.HttpError(error.code(), reason = "fanarttv_http_error")
                        else -> IntegrationLoadResult.NetworkError(error)
                    }
                }
            )
        }
        return when (val result = runtime.get(spec).valueOrNull()) {
            null -> FanartTvLookupResult.NotFound
            else -> FanartTvLookupResult.Success(result)
        }
    }

    companion object {
        private const val FANART_TV_DOC_TTL_MS = 14L * 24 * 60 * 60 * 1000

        private val documentCodec: IntegrationCodec<FanartTvDocument> by lazy {
            val moshi = Moshi.Builder().build()
            val adapter = moshi.adapter(FanartTvDocument::class.java)
            object : IntegrationCodec<FanartTvDocument> {
                override val mimeType: String = "application/json"
                override fun encode(value: FanartTvDocument): ByteArray =
                    adapter.toJson(value).toByteArray(Charsets.UTF_8)
                override fun decode(bytes: ByteArray): FanartTvDocument =
                    requireNotNull(adapter.fromJson(bytes.toString(Charsets.UTF_8))) {
                        "FanartTvDocument codec: null decoded from cache bytes"
                    }
            }
        }

        /**
         * Builds an [IntegrationSpec] for a Fanart.tv lookup with:
         * - [IntegrationCachePolicy.CacheFirst] with a 14-day TTL
         * - `api_key` redacted from all traceable surfaces — the [apiKey] value never appears
         *   in [IntegrationSpec.cacheKey] or [IntegrationSpec.operationKey]; only a
         *   16-char SHA-256 prefix ([credentialHash]) is stored.
         *
         * The [load] lambda defaults to a no-op placeholder suitable for unit tests that only
         * inspect spec metadata. The [FanartTvLookupShape.fetch] instance method supplies the
         * real Retrofit call.
         */
        fun specFor(
            callId: FanartTvCallId,
            apiKey: String,
            load: suspend () -> IntegrationLoadResult<FanartTvDocument> = {
                IntegrationLoadResult.HttpError(501, reason = "load_not_wired")
            }
        ): IntegrationSpec<FanartTvDocument> {
            val credHash = credentialHash(IntegrationProvider.FANART_TV, apiKey)
            val typeSegment = when (callId.type) {
                FanartTvCallId.Type.MOVIE -> "movie"
                FanartTvCallId.Type.TV -> "tv"
            }
            return IntegrationSpec(
                provider = IntegrationProvider.FANART_TV,
                apiShapeId = FanartTvApiShapes.LOOKUP,
                operationKey = "fanarttv.lookup.$typeSegment.${callId.value}",
                cacheKey = "fanarttv:lookup:$typeSegment:${callId.value}:credentialHash:$credHash",
                codec = documentCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = FANART_TV_DOC_TTL_MS
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = load
            )
        }
    }
}
