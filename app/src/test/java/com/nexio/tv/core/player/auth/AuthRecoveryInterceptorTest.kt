package com.nexio.tv.core.player.auth

import android.util.Log
import com.nexio.tv.core.player.CometProxyUrlResolver
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthRecoveryInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        server = MockWebServer().also { it.start() }
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CometProxyUrlResolver.resetForTesting()
        AuthRecoveryTracker.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `recovers from 401 by re-resolving and retrying once`() {
        val resolvedFirst = server.url("/cdn/first").toString()
        val resolvedSecond = server.url("/cdn/second").toString()
        val resolveCalls = AtomicInteger(0)
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            if (resolveCalls.getAndIncrement() == 0) resolvedFirst else resolvedSecond
        }

        val proxy = "https://comet.feels.legal/aBc/playback/abcd/0/0/n/n?torrent_name=x&name=y"
        runBlocking { CometProxyUrlResolver.resolve(proxy, headers = emptyMap()) }

        val recordedPaths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recordedPaths += request.path ?: ""
                return when (request.path) {
                    "/cdn/first" -> MockResponse().setResponseCode(401)
                    "/cdn/second" -> MockResponse().setResponseCode(200).setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthRecoveryInterceptor(maxAttemptsPerSession = 3))
            .build()

        val response = client.newCall(Request.Builder().url(resolvedFirst).build()).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("ok", it.body?.string())
        }
        assertEquals(listOf("/cdn/first", "/cdn/second"), recordedPaths)
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
        assertTrue(AuthRecoveryTracker.totalAttempts() >= 1)
    }
}
