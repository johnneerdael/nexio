package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import com.nexio.tv.core.player.ProxyResolution
import com.nexio.tv.ui.screens.player.spool.DiskSpoolSession
import com.nexio.tv.ui.screens.player.spool.DiskSpoolWriter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PlaybackAuthRecoveryEndToEndTest {
    @get:Rule val temp = TemporaryFolder()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `disk spool recovers transparently from mid-stream 401`() {
        val cdnA = MockWebServer().also { it.start() }
        val cdnB = MockWebServer().also { it.start() }
        val totalBytes = 4 * 1024 * 1024
        val payloadA = ByteArray(totalBytes) { 0xAA.toByte() }
        val payloadB = ByteArray(totalBytes) { 0xBB.toByte() }
        val resolveCount = AtomicInteger(0)

        try {
            // Resolver returns A first, then B after invalidate.
            CometProxyUrlResolver.setTransportForTesting { _, _ ->
                ProxyResolution.Redirected(
                    if (resolveCount.getAndIncrement() == 0)
                        cdnA.url("/movie.bin").toString()
                    else
                        cdnB.url("/movie.bin").toString()
                )
            }
            val proxy = "https://comet.feels.legal/A/playback/x/0/0/n/n?torrent_name=t&name=n"
            runBlocking {
                CometProxyUrlResolver.resolve(proxy, headers = emptyMap(), addonHost = "comet.feels.legal")
            }

            cdnA.dispatcher = makeDispatcher(payloadA, after512KbReturn401 = true)
            cdnB.dispatcher = makeDispatcher(payloadB, after512KbReturn401 = false)

            val client = OkHttpClient.Builder()
                .addInterceptor(AuthRecoveryInterceptor())
                .build()
            val writer = DiskSpoolWriter(client, chunkBytes = 256 * 1024)
            val session = DiskSpoolSession(File(temp.root, "spool.bin"), capacityBytes = totalBytes.toLong())

            try {
                writer.downloadUntil(cdnA.url("/movie.bin").toString(), session, totalBytes.toLong())
            } finally {
                session.close()
            }

            assertTrue(
                "expected at least one recovered attempt, got ${AuthRecoveryTracker.recoveredCount()}",
                AuthRecoveryTracker.recoveredCount() >= 1
            )
        } finally {
            cdnA.shutdown()
            cdnB.shutdown()
        }
    }

    private fun makeDispatcher(payload: ByteArray, after512KbReturn401: Boolean): Dispatcher {
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: ""
                if (range == "bytes=0-0") {
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${payload.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(payload[0].toInt()))
                }
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                if (after512KbReturn401 && start >= 512 * 1024) {
                    return MockResponse().setResponseCode(401)
                }
                val slice = payload.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Content-Length", slice.size)
                    .setBody(Buffer().write(slice))
            }
        }
    }
}
