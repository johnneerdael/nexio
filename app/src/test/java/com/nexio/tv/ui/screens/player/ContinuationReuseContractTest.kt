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
 * Phase 4 façade test: a non-zero open must begin serving bytes at exactly
 * `dataSpec.position`, regardless of the continuation-reuse path that may be
 * streaming bytes into the store underneath.
 */
@RunWith(RobolectricTestRunner::class)
class ContinuationReuseContractTest {

    private lateinit var server: MockWebServer
    // Deterministic content so we can verify exact byte alignment at a non-zero position.
    private val content = ByteArray(512 * 1024) { (it and 0xFF).toByte() }

    @Before
    fun start() {
        server = MockRangeServer.start(content)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `non-zero open respects exact dataSpec position`() {
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
            chunkSize = 64 * 1024L
        )
        val uri = Uri.parse(server.url("/media.bin").toString())
        val startPos = 200L * 1024L // non-zero, not chunk-aligned
        ds.open(DataSpec.Builder().setUri(uri).setPosition(startPos).build())

        val readSize = 8 * 1024
        val buf = ByteArray(readSize)
        var got = 0
        while (got < readSize) {
            val n = ds.read(buf, got, readSize - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        assertEquals(readSize, got)
        for (i in 0 until readSize) {
            val expected = ((startPos.toInt() + i) and 0xFF).toByte()
            assertEquals("byte at file offset ${startPos + i}", expected, buf[i])
        }
        ds.close()
    }
}
