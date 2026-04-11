package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

internal object PlayerPlaybackNetworking {
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @OptIn(UnstableApi::class)
    fun createDataSourceFactory(
        context: Context,
        client: OkHttpClient,
        defaultHeaders: Map<String, String> = emptyMap(),
        streamingCacheProvider: StreamingCacheProvider? = null,
        useStreamingCache: Boolean = false,
    ): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(DEFAULT_USER_AGENT)
        }
        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        if (!useStreamingCache || streamingCacheProvider == null) {
            return upstreamFactory
        }
        return CacheDataSource.Factory()
            .setCache(streamingCacheProvider.getOrCreateCache())
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
