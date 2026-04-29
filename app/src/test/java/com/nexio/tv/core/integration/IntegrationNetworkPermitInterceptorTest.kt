package com.nexio.tv.core.integration

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * F2-A-02: Verifies the [IntegrationNetworkPermitInterceptor] ENFORCE throw contract.
 *
 * When [IntegrationNetworkPermitInterceptor.Mode.ENFORCE] is active, any in-scope provider
 * request that lacks an attached [IntegrationNetworkPermit] must throw [IllegalStateException].
 * This test pins that contract so the migration from AUDIT_ONLY → ENFORCE (tracked in
 * integration-runtime-phase-c) is verifiably safe.
 */
class IntegrationNetworkPermitInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        // Enqueue a permissive response so AUDIT_ONLY tests can proceed through
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `ENFORCE mode throws when in-scope request has no permit`() {
        val host = server.hostName  // e.g. "localhost" or "127.0.0.1"
        val classifier = IntegrationHostClassifier(setOf(host))

        val client = OkHttpClient.Builder()
            .addInterceptor(
                IntegrationNetworkPermitInterceptor(
                    hostClassifier = classifier,
                    mode = IntegrationNetworkPermitInterceptor.Mode.ENFORCE
                )
            )
            .build()

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        assertThrows(
            "ENFORCE mode must throw when no permit is present on an in-scope request",
            IllegalStateException::class.java
        ) {
            client.newCall(request).execute()
        }
    }

    @Test
    fun `AUDIT_ONLY mode allows in-scope request without permit`() {
        val host = server.hostName
        val classifier = IntegrationHostClassifier(setOf(host))

        val client = OkHttpClient.Builder()
            .addInterceptor(
                IntegrationNetworkPermitInterceptor(
                    hostClassifier = classifier,
                    mode = IntegrationNetworkPermitInterceptor.Mode.AUDIT_ONLY
                )
            )
            .build()

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.close()
        } catch (e: IllegalStateException) {
            fail("AUDIT_ONLY mode must not throw for permit-less in-scope requests: ${e.message}")
        }
    }

    @Test
    fun `ENFORCE mode allows out-of-scope request without permit`() {
        // The classifier does NOT include the server host, so the request is out-of-scope
        val classifier = IntegrationHostClassifier(setOf("api.themoviedb.org"))

        val client = OkHttpClient.Builder()
            .addInterceptor(
                IntegrationNetworkPermitInterceptor(
                    hostClassifier = classifier,
                    mode = IntegrationNetworkPermitInterceptor.Mode.ENFORCE
                )
            )
            .build()

        val request = Request.Builder()
            .url(server.url("/test"))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.close()
        } catch (e: IllegalStateException) {
            fail("ENFORCE mode must not throw for out-of-scope requests: ${e.message}")
        }
    }
}
