package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import com.nexio.tv.core.artwork.fanarttv.FanartTvLookupResult
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeFanartTvLookup @Inject constructor(
    private val shape: FanartTvLookupShape
) : FanartTvLookup {
    override suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult =
        try {
            FanartTvLookupResult.Success(
                when (callId.type) {
                    FanartTvCallId.Type.MOVIE -> shape.getMovie(tmdbId = callId.value, apiKey = apiKey)
                    FanartTvCallId.Type.TV -> shape.getTv(tvdbId = callId.value, apiKey = apiKey)
                }
            )
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> FanartTvLookupResult.NotFound
                401, 403 -> FanartTvLookupResult.AuthFailed
                else -> FanartTvLookupResult.Transient
            }
        } catch (_: IOException) {
            FanartTvLookupResult.Transient
        }
}
