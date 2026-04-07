package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two responsibilities:
 *
 * 1. **`BaseDataSource` lifecycle fan-out** — verifies that
 *    `addTransferListener` actually receives `onTransferInitializing`,
 *    `onTransferStart`, `onBytesTransferred`, and `onTransferEnd` exactly once
 *    per open→drain→close cycle.
 *
 * 2. **Page-boundary deadlock regression** — pins the fix for a real product
 *    bug discovered during the anti-slop pass: `PagedFrontierBuffer` only
 *    exposes bytes once the page covering them is fully written, so when
 *    `chunkSize * (parallelConnections + 1)` is smaller than `PAGE_SIZE`
 *    the cursor can deadlock waiting for chunks that scheduling never queued.
 *    The fix lives in `SharedParallelTransportManager.promoteRanges`, which
 *    now bumps `maxAhead` to at least one full page worth of chunks.
 *    The `chunks_smaller_than_page_no_deadlock` test exercises that exact
 *    32 KB-chunk × 128 KB-page configuration.
 */
@RunWith(RobolectricTestRunner::class)
class TransferListenerFanOutTest {

    private lateinit var server: MockWebServer
    private var content: ByteArray = ByteArray(0)

    @Before fun start() {}
    @After fun stop() { if (::server.isInitialized) server.shutdown() }

    private fun startServer(size: Int) {
        content = ByteArray(size) { (it and 0xFF).toByte() }
        server = MockRangeServer.start(content)
    }

    private fun newDs(chunkSize: Long): ParallelRangeDataSource {
        val factory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        )
        return ParallelRangeDataSource(
            upstreamFactory = factory,
            parallelConnections = 2,
            chunkSize = chunkSize
        )
    }

    private fun drain(ds: ParallelRangeDataSource): Long {
        val uri = Uri.parse(server.url("/media.bin").toString())
        ds.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
        val buf = ByteArray(16 * 1024)
        var drained = 0L
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            drained += n
        }
        ds.close()
        return drained
    }

    @Test(timeout = 15_000L)
    fun `lifecycle callbacks fan out to registered listener`() {
        startServer(256 * 1024)
        val ds = newDs(64 * 1024L)

        var initializing = 0
        var started = 0
        var ended = 0
        var bytesEvents = 0
        var bytesTotal = 0L
        ds.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(s: DataSource, d: DataSpec, n: Boolean) { initializing += 1 }
            override fun onTransferStart(s: DataSource, d: DataSpec, n: Boolean) { started += 1 }
            override fun onBytesTransferred(s: DataSource, d: DataSpec, n: Boolean, b: Int) {
                bytesEvents += 1; bytesTotal += b
            }
            override fun onTransferEnd(s: DataSource, d: DataSpec, n: Boolean) { ended += 1 }
        })

        val drained = drain(ds)
        assertEquals(content.size.toLong(), drained)
        assertTrue("initializing should fire at least once, got $initializing", initializing >= 1)
        assertEquals("transferStart must fire exactly once", 1, started)
        assertTrue("bytesTransferred should fire at least once", bytesEvents >= 1)
        assertEquals("bytesTransferred total must equal drained bytes", drained, bytesTotal)
        assertEquals("transferEnd must fire exactly once", 1, ended)
    }

    @Test(timeout = 15_000L)
    fun `chunks smaller than page do not deadlock the cursor`() {
        // 128 KB content, 32 KB chunks, 2 parallel connections.
        // Pre-fix: 4 chunks per page, only 3 scheduled ahead → deadlock at 60s timeout.
        // Post-fix: maxAhead bumped to chunksPerPage+1 → all 4 chunks scheduled → drain succeeds.
        startServer(128 * 1024)
        val drained = drain(newDs(32 * 1024L))
        assertEquals(content.size.toLong(), drained)
    }
}
