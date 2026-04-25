package com.nexio.tv.core.player.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EgressIpFingerprintTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `samples ip from probe endpoint`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        val sample = fp.sampleNow()
        assertEquals("1.2.3.4", sample)
    }

    @Test
    fun `compare returns Changed when ip differs from baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("5.6.7.8"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        fp.captureBaseline()
        val state = fp.compareNow()
        assertEquals(EgressIpFingerprint.State.Changed("1.2.3.4", "5.6.7.8"), state)
    }

    @Test
    fun `compare returns Stable when ip matches baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        fp.captureBaseline()
        assertEquals(EgressIpFingerprint.State.Stable("1.2.3.4"), fp.compareNow())
    }

    @Test
    fun `compare returns Unknown when baseline was never captured`() {
        val fp = EgressIpFingerprint(OkHttpClient(), "http://127.0.0.1:1/ip")
        assertEquals(EgressIpFingerprint.State.Unknown, fp.compareNow())
    }

    @Test
    fun `compare returns Unknown when probe fails after baseline was captured`() {
        // Baseline succeeds; second probe fails because the server is shut down
        // before compareNow runs.
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fp = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())
        fp.captureBaseline()
        server.shutdown()
        assertEquals(EgressIpFingerprint.State.Unknown, fp.compareNow())
    }
}
