package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Phase 4 façade test: bootstrap reuse must publish its bytes through
 * [AbsoluteByteStore] aligned to the current open session's start position,
 * so the first Media3 reads come from the cache — not from a fresh upstream fetch.
 */
@RunWith(RobolectricTestRunner::class)
class BootstrapReuseContractTest {

    private lateinit var server: MockWebServer
    // Server content is distinct from the bootstrap marker so we can tell which path served the bytes.
    private val serverContent = ByteArray(1 * 1024 * 1024) { 0x00.toByte() }

    @Before
    fun start() {
        server = MockRangeServer.start(serverContent)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `bootstrap bytes are aligned to current open session and served first`() {
        val bootstrapSize = 64 * 1024
        val bootstrap = ByteArray(bootstrapSize) { 0x5A.toByte() } // distinct marker
        val uri = Uri.parse(server.url("/media.bin").toString())

        val factory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        )
        val ds = ParallelRangeDataSource(
            upstreamFactory = factory,
            parallelConnections = 2,
            chunkSize = 256 * 1024L,
            consumeBootstrapCache = { spec ->
                ParallelRangeDataSource.BootstrapCacheEntry(
                    requestUri = spec.uri,
                    startPosition = 0L,
                    resolvedUri = uri,
                    openLength = serverContent.size.toLong(),
                    totalFileLength = serverContent.size.toLong(),
                    bootstrapData = bootstrap,
                    bootstrapSize = bootstrapSize,
                    createdAtUptimeMs = android.os.SystemClock.uptimeMillis()
                )
            }
        )

        val openLen = ds.open(DataSpec.Builder().setUri(uri).build())
        assertEquals(serverContent.size.toLong(), openLen)

        // Drain the full bootstrap window; every byte must match the marker exactly.
        val bootBuf = ByteArray(bootstrapSize)
        var got = 0
        while (got < bootstrapSize) {
            val n = ds.read(bootBuf, got, bootstrapSize - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        assertEquals(bootstrapSize, got)
        for (i in 0 until bootstrapSize) {
            assertEquals("byte $i should come from bootstrap marker", 0x5A.toByte(), bootBuf[i])
        }

        // Continue past the bootstrap window into upstream territory; bytes must be 0x00
        // (the server's content marker), proving bootstrap → upstream handoff works.
        val tailSize = 16 * 1024
        val tailBuf = ByteArray(tailSize)
        var tailGot = 0
        while (tailGot < tailSize) {
            val n = ds.read(tailBuf, tailGot, tailSize - tailGot)
            if (n == C.RESULT_END_OF_INPUT) break
            tailGot += n
        }
        assertEquals(tailSize, tailGot)
        for (i in 0 until tailSize) {
            assertEquals("byte $i past bootstrap should come from upstream", 0x00.toByte(), tailBuf[i])
        }
        ds.close()
    }
}
