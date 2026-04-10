package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceReopenCostTest {

    @Test(timeout = 10_000L)
    fun `internal non zero reopen does not rebuild PRDS from scratch when reopen stays within active coverage`() {
        val content = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = startRangeServer(content)
        val probe = ReopenCostProbe()
        val dataSource = newInstrumentedDataSource(probe)

        try {
            dataSource.open(spec(server, position = 0L))
            dataSource.close()

            val reopenedLength = dataSource.open(spec(server, position = 1024L))
            try {
                assertTrue(reopenedLength > 0L || reopenedLength == C.LENGTH_UNSET.toLong())
                val buffer = ByteArray(1)
                assertEquals(1, dataSource.read(buffer, 0, buffer.size))
            } finally {
                dataSource.close()
            }
        } finally {
            server.shutdown()
        }

        assertEquals(1, probe.transportAttachCount)
        assertEquals(0, probe.storeResetCount)
    }

    @Test(timeout = 10_000L)
    fun `bounded extractor probe reopen does not rebuild transport`() {
        val content = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = startRangeServer(content)
        val probe = ReopenCostProbe()
        val dataSource = newInstrumentedDataSource(probe)

        try {
            dataSource.open(spec(server, position = 0L))
            dataSource.close()

            val reopenedLength = dataSource.open(
                spec(
                    server = server,
                    position = content.size.toLong() - 64L * 1024L,
                    length = 64L * 1024L,
                )
            )
            try {
                assertEquals(64L * 1024L, reopenedLength)
            } finally {
                dataSource.close()
            }
        } finally {
            server.shutdown()
        }

        assertEquals(1, probe.transportAttachCount)
        assertEquals(0, probe.storeResetCount)
    }

    private fun newInstrumentedDataSource(probe: ReopenCostProbe): ParallelRangeDataSource {
        return ParallelRangeDataSource(
            upstreamFactory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()
            ),
            envelope = CapabilityEnvelope.DEFAULT,
            parallelConnections = 1,
            chunkSize = 8L * 1024L * 1024L,
            chunkWaitTimeoutMs = 5_000L,
            onTransportSessionAttachedForTest = { probe.transportAttachCount += 1 },
            onStoreResetForTest = { probe.storeResetCount += 1 }
        )
    }

    private fun spec(server: MockWebServer, position: Long, length: Long = C.LENGTH_UNSET.toLong()): DataSpec {
        return DataSpec.Builder()
            .setUri(server.url("/media.bin").toString())
            .setPosition(position)
            .setLength(length)
            .build()
    }

    private fun startRangeServer(content: ByteArray): MockWebServer {
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val rangeHeader = request.getHeader("Range")
                    return if (rangeHeader == null) {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("Content-Length", content.size)
                            .setBody(Buffer().write(content))
                    } else {
                        rangedResponse(content, rangeHeader)
                    }
                }
            }
            start()
        }
    }

    private fun rangedResponse(content: ByteArray, rangeHeader: String): MockResponse {
        val match = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
            ?: error("Unexpected Range header: $rangeHeader")
        val start = match.groupValues[1].toLong()
        val requestedEnd = match.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
        val endExclusive = minOf((requestedEnd?.plus(1L)) ?: content.size.toLong(), content.size.toLong())
        val length = (endExclusive - start).toInt()
        if (length <= 0 || start >= content.size) {
            return MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */${content.size}")
        }
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
            .setHeader("Content-Length", length)
            .setBody(Buffer().write(content, start.toInt(), length))
    }

    private class ReopenCostProbe {
        var transportAttachCount: Int = 0
        var storeResetCount: Int = 0
    }
}
