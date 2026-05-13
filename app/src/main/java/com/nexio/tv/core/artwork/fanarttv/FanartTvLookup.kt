package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument

interface FanartTvLookup {
    suspend fun fetch(callId: FanartTvCallId, apiKey: String): FanartTvLookupResult
}

sealed interface FanartTvLookupResult {
    data class Success(val document: FanartTvDocument) : FanartTvLookupResult
    data object NotFound : FanartTvLookupResult
    data object AuthFailed : FanartTvLookupResult
    data object Transient : FanartTvLookupResult
}
