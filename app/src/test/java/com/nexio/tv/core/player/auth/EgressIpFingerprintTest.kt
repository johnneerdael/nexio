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
        val fingerprint = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())

        assertEquals("1.2.3.4", fingerprint.sampleNow())
    }

    @Test
    fun `compare returns changed when ip differs from baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("5.6.7.8"))
        val fingerprint = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())

        fingerprint.captureBaseline()

        assertEquals(
            EgressIpFingerprint.State.Changed("1.2.3.4", "5.6.7.8"),
            fingerprint.compareNow()
        )
    }

    @Test
    fun `compare returns stable when ip matches baseline`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fingerprint = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())

        fingerprint.captureBaseline()

        assertEquals(EgressIpFingerprint.State.Stable("1.2.3.4"), fingerprint.compareNow())
    }

    @Test
    fun `compare returns unknown when baseline was never captured`() {
        val fingerprint = EgressIpFingerprint(OkHttpClient(), "http://127.0.0.1:1/ip")

        assertEquals(EgressIpFingerprint.State.Unknown, fingerprint.compareNow())
    }

    @Test
    fun `compare returns unknown when probe fails after baseline was captured`() {
        server.enqueue(MockResponse().setBody("1.2.3.4"))
        val fingerprint = EgressIpFingerprint(OkHttpClient(), server.url("/ip").toString())

        fingerprint.captureBaseline()
        server.shutdown()

        assertEquals(EgressIpFingerprint.State.Unknown, fingerprint.compareNow())
    }
}
