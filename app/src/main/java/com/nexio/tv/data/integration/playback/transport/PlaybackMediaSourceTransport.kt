package com.nexio.tv.data.integration.playback.transport

import androidx.media3.datasource.okhttp.OkHttpDataSource
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient

class PlaybackMediaSourceTransport @Inject constructor(
    @Named("playback") private val okHttpClient: OkHttpClient
) {
    internal val diskSpoolHttpTransport: DiskSpoolHttpTransport = OkHttpDiskSpoolHttpTransport(okHttpClient)

    fun createDataSourceFactory(
        headers: Map<String, String>,
        defaultUserAgent: String
    ): OkHttpDataSource.Factory {
        return OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(headers)
            if (!headers.containsKey("User-Agent")) {
                setUserAgent(defaultUserAgent)
            }
        }
    }
}
