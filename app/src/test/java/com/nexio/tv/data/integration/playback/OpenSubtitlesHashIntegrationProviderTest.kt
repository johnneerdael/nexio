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
import com.nexio.tv.data.integration.playback.transport.OpenSubtitlesHashTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesHashIntegrationProviderTest {

    @Test
    fun `hash request executes through runtime`() = runTest {
        val runtime = RecordingRuntime()
        val transportThread = AtomicReference<String>()
        val transport = FakeOpenSubtitlesHashTransport(onRead = {
            transportThread.set(Thread.currentThread().name)
        })
        val provider = OpenSubtitlesHashIntegrationProvider(
            runtime = runtime,
            transport = transport
        )
        val callerThread = Thread.currentThread().name

        val result = provider.compute("https://example.test/movie.mkv", emptyMap())

        assertEquals("0000000000026000", result?.hash)
        assertEquals(131072L, result?.fileSize)
        assertEquals(listOf(0L..65_535L, 65_536L..131_071L), transport.requests)
        assertEquals(1, runtime.calls.size)
        assertEquals(IntegrationWorkClass.PLAYBACK_RESOLUTION, runtime.calls.single().workClass)
        assertEquals(IntegrationScope.GlobalContent, runtime.calls.single().scope)
        assertTrue(transportThread.get()?.isNotBlank() == true)
        assertFalse(transportThread.get() == callerThread)
    }

    @Test
    fun `provider returns null when transport rejects non-ranged response`() = runTest {
        val provider = OpenSubtitlesHashIntegrationProvider(
            runtime = RecordingRuntime(),
            transport = object : OpenSubtitlesHashTransport {
                override fun contentLength(url: String, headers: Map<String, String>): Long = 131_072L

                override fun readRange(
                    url: String,
                    headers: Map<String, String>,
                    start: Long,
                    endInclusive: Long
                ): ByteArray {
                    throw IOException("Range request not honored")
                }
            }
        )

        assertNull(provider.compute("https://example.test/movie.mkv", emptyMap()))
    }

    @Test
    fun `provider returns null when file is too small`() = runTest {
        val provider = OpenSubtitlesHashIntegrationProvider(
            runtime = RecordingRuntime(),
            transport = object : OpenSubtitlesHashTransport {
                override fun contentLength(url: String, headers: Map<String, String>): Long = 65_535L

                override fun readRange(
                    url: String,
                    headers: Map<String, String>,
                    start: Long,
                    endInclusive: Long
                ): ByteArray = error("should not read")
            }
        )

        assertNull(provider.compute("https://example.test/movie.mkv", emptyMap()))
    }

    @Test
    fun `provider rethrows cancellation from transport`() = runTest {
        val provider = OpenSubtitlesHashIntegrationProvider(
            runtime = RecordingRuntime(),
            transport = object : OpenSubtitlesHashTransport {
                override fun contentLength(url: String, headers: Map<String, String>): Long {
                    throw CancellationException("cancel hash")
                }

                override fun readRange(
                    url: String,
                    headers: Map<String, String>,
                    start: Long,
                    endInclusive: Long
                ): ByteArray = error("should not read")
            }
        )

        val result = async {
            provider.compute("https://example.test/movie.mkv", emptyMap())
        }

        try {
            result.await()
            throw AssertionError("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel hash", expected.message)
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

    private class FakeOpenSubtitlesHashTransport(
        private val onRead: (() -> Unit)? = null
    ) : OpenSubtitlesHashTransport {
        val requests = mutableListOf<LongRange>()

        override fun contentLength(url: String, headers: Map<String, String>): Long = 131_072L

        override fun readRange(
            url: String,
            headers: Map<String, String>,
            start: Long,
            endInclusive: Long
        ): ByteArray {
            onRead?.invoke()
            requests += start..endInclusive
            return when {
                start == 0L && endInclusive == 65_535L -> chunkWithRepeatedLong(1L)
                start == 65_536L && endInclusive == 131_071L -> chunkWithRepeatedLong(2L)
                else -> error("Unexpected range: $start-$endInclusive")
            }
        }
    }

    companion object {
        private fun chunkWithRepeatedLong(value: Long): ByteArray {
            val chunk = ByteArray(65_536)
            var offset = 0
            while (offset < chunk.size) {
                for (index in 0 until 8) {
                    chunk[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
                }
                offset += 8
            }
            return chunk
        }
    }
}
