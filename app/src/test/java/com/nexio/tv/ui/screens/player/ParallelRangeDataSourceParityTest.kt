package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Phase 1 parity tests: compare ParallelRangeDataSource output against a plain OkHttpDataSource
 * on the same MockWebServer payload. Tests document the current gap (if any) without hiding
 * failures — do NOT @Ignore any test here.
 */
@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceParityTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    companion object {
        private val CONTENT: ByteArray = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        // Chunk size small enough to guarantee multi-chunk paths even on short reads.
        private const val TEST_CHUNK_SIZE = 128 * 1024L   // 128 KiB
        private const val TEST_PARALLEL = 2
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildOkHttpFactory(server: MockWebServer): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
        )

    private fun buildPrds(factory: OkHttpDataSource.Factory): ParallelRangeDataSource =
        ParallelRangeDataSource(
            upstreamFactory = factory,
            parallelConnections = TEST_PARALLEL,
            chunkSize = TEST_CHUNK_SIZE,
        )

    private fun buildSingleSource(factory: OkHttpDataSource.Factory): OkHttpDataSource =
        factory.createDataSource()

    private fun readAll(source: DataSource, spec: DataSpec): ByteArray {
        val sink = Buffer()
        val buf = ByteArray(32 * 1024)
        source.open(spec)
        try {
            while (true) {
                val read = source.read(buf, 0, buf.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) sink.write(buf, 0, read)
            }
        } finally {
            source.close()
        }
        return sink.readByteArray()
    }

    private fun specAt(url: String, position: Long = 0L): DataSpec =
        DataSpec.Builder()
            .setUri(url)
            .setPosition(position)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun read_sequential_from_zero_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specAt(url)

            val prds = buildPrds(factory)
            val single = buildSingleSource(factory)

            val prdsBytes = readAll(prds, spec)
            val singleBytes = readAll(single, spec)

            assertArrayEquals(singleBytes, prdsBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun read_with_non_zero_startPosition_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val position = 256 * 1024L  // 256 KiB
            val spec = specAt(url, position)

            val prds = buildPrds(factory)
            val single = buildSingleSource(factory)

            val prdsBytes = readAll(prds, spec)
            val singleBytes = readAll(single, spec)

            assertArrayEquals(singleBytes, prdsBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun read_spanning_multiple_chunks_is_contiguous_and_ordered() {
        // Use content larger than one chunk so multiple parallel ranges are fetched.
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specAt(url)

            val prds = buildPrds(factory)
            val prdsBytes = readAll(prds, spec)

            // Basic sanity: same length as content
            assertEquals(CONTENT.size, prdsBytes.size)
            // Verify contiguous ordering byte-by-byte against source of truth
            assertArrayEquals(CONTENT, prdsBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun read_at_eof_returns_RESULT_END_OF_INPUT() {
        // Read the entire content, then verify that the loop terminated via RESULT_END_OF_INPUT
        // (readAll() only exits on RESULT_END_OF_INPUT — if it hangs, the @Rule timeout fires).
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specAt(url)

            val prds = buildPrds(factory)
            val bytes = readAll(prds, spec)

            // If we get here, RESULT_END_OF_INPUT was returned properly.
            assertEquals(CONTENT.size, bytes.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getUri_after_open_returns_resolved_uri() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specAt(url)

            val prds = buildPrds(factory)
            prds.open(spec)
            try {
                val uri = prds.uri
                // URI must be non-null and contain the same host/path
                assert(uri != null) { "uri was null after open()" }
                assert(uri.toString().contains("media.bin")) {
                    "Expected uri to contain 'media.bin' but was: $uri"
                }
            } finally {
                prds.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun close_after_partial_read_is_idempotent() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specAt(url)

            val prds = buildPrds(factory)
            prds.open(spec)

            // Read 4 KiB then close twice — must not throw
            val buf = ByteArray(4 * 1024)
            var read = 0
            while (read < buf.size) {
                val n = prds.read(buf, read, buf.size - read)
                if (n == C.RESULT_END_OF_INPUT) break
                if (n > 0) read += n
            }

            prds.close()   // first close
            // After close, the PRDS contract requires getUri() to return null and
            // responseHeaders to be empty.
            assertEquals(null, prds.uri)
            assertTrue("responseHeaders should be empty after close", prds.responseHeaders.isEmpty())
            prds.close()   // second close — must be idempotent
            assertEquals(null, prds.uri)
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // P0-2 parity cases
    // ---------------------------------------------------------------------------

    private fun specBounded(url: String, position: Long, length: Long): DataSpec =
        DataSpec.Builder()
            .setUri(url)
            .setPosition(position)
            .setLength(length)
            .build()

    @Test
    fun bounded_length_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val spec = specBounded(url, position = 1024L, length = 16 * 1024L)

            val prds = buildPrds(factory)
            val single = buildSingleSource(factory)

            val prdsBytes = readAll(prds, spec)
            val singleBytes = readAll(single, spec)

            assertArrayEquals(singleBytes, prdsBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reopen_after_seek_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val prds = buildPrds(factory)

            // First open at 0, drain a partial chunk
            prds.open(specAt(url, 0L))
            val partial = ByteArray(8 * 1024)
            var got = 0
            while (got < partial.size) {
                val n = prds.read(partial, got, partial.size - got)
                if (n == C.RESULT_END_OF_INPUT) break
                if (n > 0) got += n
            }
            prds.close()

            // Reopen *same* PRDS instance at non-zero position
            val seekPos = 384 * 1024L
            val seekSpec = specAt(url, seekPos)
            val prdsBytes = readAll(prds, seekSpec)

            val single = buildSingleSource(factory)
            val singleBytes = readAll(single, seekSpec)

            assertArrayEquals(singleBytes, prdsBytes)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun redirect_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val redirectUrl = server.url("/redirect").toString()
            val directUrl = server.url("/media.bin").toString()

            val prds = buildPrds(factory)
            val prdsBytes = readAll(prds, specAt(redirectUrl))

            val single = buildSingleSource(factory)
            val singleBytes = readAll(single, specAt(directUrl))

            assertArrayEquals(singleBytes, prdsBytes)

            // Verify resolved URI reflects the redirect target
            prds.open(specAt(redirectUrl))
            try {
                val resolved = prds.uri?.toString() ?: ""
                assertTrue(
                    "Expected resolved URI to point at /media.bin but was: $resolved",
                    resolved.contains("media.bin")
                )
            } finally {
                prds.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @get:Rule(order = 1)
    val eofTimeout: Timeout = Timeout.seconds(5)

    @Test
    fun position_at_eof_returns_immediate_eof() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val prds = buildPrds(factory)

            // position == content.size is a valid open that immediately yields EOF.
            prds.open(specAt(url, CONTENT.size.toLong()))
            try {
                val buf = ByteArray(4 * 1024)
                val n = prds.read(buf, 0, buf.size)
                assertEquals(C.RESULT_END_OF_INPUT, n)
            } finally {
                prds.close()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun position_beyond_eof_throws_position_out_of_range() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val prds = buildPrds(factory)

            try {
                prds.open(specAt(url, CONTENT.size.toLong() + 1L))
                org.junit.Assert.fail("expected DataSourceException POSITION_OUT_OF_RANGE")
            } catch (e: androidx.media3.datasource.DataSourceException) {
                assertEquals(
                    androidx.media3.datasource.DataSourceException.POSITION_OUT_OF_RANGE,
                    e.reason
                )
            } finally {
                runCatching { prds.close() }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun non_zero_dest_offset_matches_single_connection() {
        val server = MockRangeServer.start(CONTENT)
        try {
            val factory = buildOkHttpFactory(server)
            val url = server.url("/media.bin").toString()
            val prds = buildPrds(factory)

            prds.open(specAt(url, 0L))
            try {
                val sentinel = 0x7E.toByte()
                val dest = ByteArray(64 + 4096) { sentinel }
                // Read into buffer starting at offset 64
                var totalRead = 0
                while (totalRead < 4096) {
                    val n = prds.read(dest, 64 + totalRead, 4096 - totalRead)
                    if (n == C.RESULT_END_OF_INPUT) break
                    if (n > 0) totalRead += n
                }
                assertEquals(4096, totalRead)
                // Prefix [0, 64) untouched
                for (i in 0 until 64) {
                    assertEquals("byte $i should be untouched sentinel", sentinel, dest[i])
                }
                // Bytes [64, 64+4096) match source
                val expected = CONTENT.copyOfRange(0, 4096)
                val actual = dest.copyOfRange(64, 64 + 4096)
                assertArrayEquals(expected, actual)
            } finally {
                prds.close()
            }
        } finally {
            server.shutdown()
        }
    }
}
