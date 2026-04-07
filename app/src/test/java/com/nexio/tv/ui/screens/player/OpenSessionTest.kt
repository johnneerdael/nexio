package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Phase 4 façade tests for [OpenSession] and the session-local metadata surface
 * on [ParallelRangeDataSource]. Verifies exact start/end semantics, resolved URI
 * and response-header preservation, and failed-open then close behavior.
 */
@RunWith(RobolectricTestRunner::class)
class OpenSessionTest {

    private lateinit var server: MockWebServer
    private val content = ByteArray(64 * 1024) { (it and 0xFF).toByte() }

    @Before
    fun start() {
        server = MockRangeServer.start(content)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun newDataSource(): ParallelRangeDataSource {
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
            chunkSize = 16 * 1024L
        )
    }

    @Test
    fun `resolvedUri is preserved after successful open`() {
        val ds = newDataSource()
        val uri = Uri.parse(server.url("/media.bin").toString())
        ds.open(DataSpec.Builder().setUri(uri).build())
        assertEquals(uri, ds.uri)
        // Content-Range header should be visible on a ranged response.
        // Headers for a full GET will expose Accept-Ranges.
        val headers = ds.responseHeaders
        assertNotNull(headers)
        assertTrue(
            "Expected Accept-Ranges or Content-Range header, got: ${headers.keys}",
            headers.keys.any { it.equals("Accept-Ranges", ignoreCase = true) } ||
                headers.keys.any { it.equals("Content-Range", ignoreCase = true) }
        )
        ds.close()
    }

    @Test
    fun `failed open then close surfaces requested uri`() {
        val ds = newDataSource()
        val badUri = Uri.parse(server.url("/notfound/missing.bin").toString())
        try {
            ds.open(DataSpec.Builder().setUri(badUri).build())
            fail("Expected failure for 404")
        } catch (_: HttpDataSource.InvalidResponseCodeException) {
            // expected
        }
        // Contract: getUri() after a failed open must return the requested URI.
        assertEquals(badUri, ds.uri)
        ds.close()
    }
}
