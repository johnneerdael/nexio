package com.nexio.tv.core.network

import com.nexio.tv.core.di.NetworkModule
import com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor
import com.nexio.tv.core.trace.RuntimeTraceInterceptor
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-I-05 part 2: directly verify both YouTube trailer OkHttpClients carry the
 * RuntimeTraceInterceptor in their interceptor lists. Pairs with the architecture-scan
 * pin in DerivedOkHttpClientTraceWiringTest.
 */
class YouTubeTrailerClientTraceInterceptorTest {

    private fun stubTagging(): RuntimeTraceContextRequestTaggingInterceptor = mockk(relaxed = true)
    private fun stubTrace(): RuntimeTraceInterceptor = mockk(relaxed = true)

    @Test
    fun `provideYouTubeTrailerMainOkHttpClient wires trace + tagging interceptors`() {
        val tagging = stubTagging()
        val trace = stubTrace()

        val client: OkHttpClient = NetworkModule.provideYouTubeTrailerMainOkHttpClient(
            taggingInterceptor = tagging,
            traceInterceptor = trace
        )

        assertCarriesInterceptors(client, tagging = tagging, trace = trace, label = "main")
    }

    @Test
    fun `provideYouTubeTrailerProbeOkHttpClient wires trace + tagging interceptors`() {
        val tagging = stubTagging()
        val trace = stubTrace()

        val client: OkHttpClient = NetworkModule.provideYouTubeTrailerProbeOkHttpClient(
            taggingInterceptor = tagging,
            traceInterceptor = trace
        )

        assertCarriesInterceptors(client, tagging = tagging, trace = trace, label = "probe")
    }

    private fun assertCarriesInterceptors(
        client: OkHttpClient,
        tagging: RuntimeTraceContextRequestTaggingInterceptor,
        trace: RuntimeTraceInterceptor,
        label: String
    ) {
        val appInterceptors: List<Interceptor> = client.interceptors
        val networkInterceptors: List<Interceptor> = client.networkInterceptors

        assertTrue(
            "F-I-05: $label trailer client must carry the tagging interceptor as an application interceptor. " +
                "Got app=${appInterceptors.map { it::class.java.simpleName }}",
            appInterceptors.any { it === tagging }
        )
        assertTrue(
            "F-I-05: $label trailer client must carry the trace interceptor as a network interceptor. " +
                "Got network=${networkInterceptors.map { it::class.java.simpleName }}",
            networkInterceptors.any { it === trace }
        )
    }
}
