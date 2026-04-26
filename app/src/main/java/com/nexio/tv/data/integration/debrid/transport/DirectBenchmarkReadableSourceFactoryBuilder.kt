package com.nexio.tv.data.integration.debrid.transport

import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.repository.benchmark.BenchmarkReadableSourceFactory
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidate
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.Media3BenchmarkReadableSource
import com.nexio.tv.data.repository.benchmark.OptimizedBenchmarkDataSourceFactoryBuilder
import javax.inject.Inject
import javax.inject.Named
import okhttp3.OkHttpClient

internal class DirectBenchmarkReadableSourceFactoryBuilder @Inject constructor(
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
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(candidate.headers)
        }
        return BenchmarkReadableSourceFactory {
            Media3BenchmarkReadableSource(
                dataSource = upstreamFactory.createDataSource(),
                candidate = candidate
            )
        }
    }
}
