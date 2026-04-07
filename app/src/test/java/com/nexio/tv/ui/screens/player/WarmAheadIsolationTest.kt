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
 * Phase 4 façade test: warm-ahead may publish bytes past the cursor, but it
 * must NOT alter the cursor/EOF semantics. A bounded open must EOF at exactly
 * dataSpec.length bytes even when the store already holds more bytes beyond.
 */
@RunWith(RobolectricTestRunner::class)
class WarmAheadIsolationTest {

    private lateinit var server: MockWebServer
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
    fun `bounded open eofs at range end regardless of warm-ahead`() {
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
        val rangeLength = 32L * 1024L
        val openLen = ds.open(
            DataSpec.Builder().setUri(uri).setPosition(0).setLength(rangeLength).build()
        )
        assertEquals(rangeLength, openLen)

        // Drain exactly rangeLength bytes.
        val buf = ByteArray(4 * 1024)
        var total = 0L
        while (total < rangeLength) {
            val want = minOf(buf.size.toLong(), rangeLength - total).toInt()
            val n = ds.read(buf, 0, want)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        assertEquals(rangeLength, total)

        // Give warm-ahead workers time to actually publish bytes past `rangeLength`
        // into the shared store. A fixed short sleep is deliberate: `store` is private
        // to ParallelRangeDataSource, so we can't poll the frontier from the test, but
        // a 250ms window is plenty for the scheduler to fire a prefetch range against
        // the in-process MockWebServer. If warm-ahead isolation is broken, those
        // published bytes will leak into the EOF read below and flip the assertion.
        Thread.sleep(250L)

        // Next read must EOF — warm-ahead bytes past the range must not extend it.
        assertEquals(C.RESULT_END_OF_INPUT, ds.read(buf, 0, buf.size))
        ds.close()
    }
}
