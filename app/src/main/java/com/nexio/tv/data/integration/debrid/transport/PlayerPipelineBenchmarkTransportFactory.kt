package com.nexio.tv.data.integration.debrid.transport

import android.content.Context
import com.nexio.tv.data.integration.playback.transport.PlaybackMediaSourceTransport
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode
import com.nexio.tv.data.repository.benchmark.BenchmarkReadableSourceFactory
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidate
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.Media3BenchmarkReadableSource
import com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkDataSourceFactoryBuilder
import com.nexio.tv.ui.screens.player.PlayerMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient

internal class PlayerPipelineBenchmarkTransportFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("benchmark") private val okHttpClient: OkHttpClient
) : OptimizedBenchmarkDataSourceFactoryBuilder {

    override fun create(
        candidate: DebridBenchmarkCandidate,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot,
        chunkWaitTimeoutMs: Long,
        allowStartupBootstrapReuse: Boolean,
        transportSampleTimeMs: () -> Long,
        onTransportBytesDownloaded: (Long, Long) -> Unit,
        onChunkBytesDownloaded: (Long, Long, Long, Int, Long) -> Unit
    ): BenchmarkReadableSourceFactory {
        val dataSourceFactory = PlayerMediaSourceFactory(
            context = context,
            playbackMediaSourceTransport = PlaybackMediaSourceTransport(okHttpClient)
        ).createBenchmarkProgressiveDataSourceFactory(
            url = candidate.directUrl,
            headers = candidate.headers,
            parallelConnectionsEnabled = configSnapshot.parallelConnectionsEnabled
                ?: PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS,
            parallelConnectionCount = configSnapshot.parallelConnectionCount
                ?: PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
            parallelChunkSizeMb = configSnapshot.parallelChunkSizeMb
                ?: PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB,
            vodCacheEnabled = configSnapshot.vodCacheEnabled
                ?: (PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE == VodCacheSizeMode.ON),
            allowStartupBootstrapReuse = allowStartupBootstrapReuse,
            transportSampleTimeMs = transportSampleTimeMs,
            onTransportBytesDownloaded = onTransportBytesDownloaded
        )
        return BenchmarkReadableSourceFactory {
            Media3BenchmarkReadableSource(
                dataSource = dataSourceFactory.createDataSource(),
                candidate = candidate
            )
        }
    }
}
