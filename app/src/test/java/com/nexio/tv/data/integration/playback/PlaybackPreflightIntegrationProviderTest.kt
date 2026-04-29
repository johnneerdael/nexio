package com.nexio.tv.data.integration.playback

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.playback.transport.PlaybackProbeResult
import com.nexio.tv.data.integration.playback.transport.PlaybackProbeTransport
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreflightIntegrationProviderTest {

    @Test
    fun `stremthru failed redirect is executed through runtime`() = runTest {
        val runtime = RecordingRuntime()
        val transportThread = AtomicReference<String>()
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = runtime,
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? {
                    transportThread.set(Thread.currentThread().name)
                    return "/static/download_failed.mp4"
                }

                override fun rangeProbe(url: String): PlaybackProbeResult = PlaybackProbeResult.Success
            }
        )
        val callerThread = Thread.currentThread().name

        assertFalse(provider.isPlayable("https://cdn.stremthru.example/video.mp4"))
        assertEquals(1, runtime.calls.size)
        assertEquals(IntegrationWorkClass.PLAYBACK_RESOLUTION, runtime.calls.single().workClass)
        assertEquals(IntegrationScope.GlobalContent, runtime.calls.single().scope)
        assertTrue(transportThread.get()?.isNotBlank() == true)
        assertFalse(transportThread.get() == callerThread)
    }

    @Test
    fun `non stremthru url does not perform network preflight`() = runTest {
        val runtime = RecordingRuntime()
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = runtime,
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? = error("should not probe")

                override fun rangeProbe(url: String): PlaybackProbeResult = error("should not probe")
            }
        )

        assertTrue(provider.isPlayable("https://example.test/video.mp4"))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `signed url skips runtime range probe`() = runTest {
        val runtime = RecordingRuntime()
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = runtime,
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? = error("should not probe")

                override fun rangeProbe(url: String): PlaybackProbeResult = error("should not probe")
            }
        )

        assertTrue(provider.rangeProbeWorks("https://example.test/video.m3u8?signature=token"))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `unsigned url routes range probe through runtime`() = runTest {
        val runtime = RecordingRuntime()
        val transportThread = AtomicReference<String>()
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = runtime,
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? = error("should not probe")

                override fun rangeProbe(url: String): PlaybackProbeResult {
                    transportThread.set(Thread.currentThread().name)
                    return PlaybackProbeResult.Success
                }
            }
        )
        val callerThread = Thread.currentThread().name

        assertTrue(provider.rangeProbeWorks("https://example.test/video.m3u8"))
        assertEquals(1, runtime.calls.size)
        assertEquals(IntegrationWorkClass.PLAYBACK_RESOLUTION, runtime.calls.single().workClass)
        assertTrue(transportThread.get()?.isNotBlank() == true)
        assertFalse(transportThread.get() == callerThread)
    }

    @Test
    fun `isPlayable rethrows cancellation from redirect probe`() = runTest {
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = RecordingRuntime(),
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? {
                    throw CancellationException("cancel redirect probe")
                }

                override fun rangeProbe(url: String): PlaybackProbeResult = PlaybackProbeResult.Success
            }
        )

        val result = async {
            provider.isPlayable("https://cdn.stremthru.example/video.mp4")
        }

        try {
            result.await()
            throw AssertionError("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel redirect probe", expected.message)
        }
    }

    @Test
    fun `rangeProbeWorks rethrows cancellation from range probe`() = runTest {
        val provider = PlaybackPreflightIntegrationProvider(
            runtime = RecordingRuntime(),
            transport = object : PlaybackProbeTransport() {
                override fun redirectLocationForHead(url: String): String? = null

                override fun rangeProbe(url: String): PlaybackProbeResult {
                    throw CancellationException("cancel range probe")
                }
            }
        )

        val result = async {
            provider.rangeProbeWorks("https://example.test/video.m3u8")
        }

        try {
            result.await()
            throw AssertionError("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel range probe", expected.message)
        }
    }

    private class RecordingRuntime : IntegrationRuntime {
        val calls = mutableListOf<IntegrationCallSpec<*>>()

        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> = IntegrationFetchResult.Missing

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> {
            calls += spec
            return spec.call()
        }

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            spec.open()
    }
}
