package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridBenchmarkStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkServiceTest {

    @Test
    fun `service rejects a second benchmark while one is already active`() = runTest {
        val service = buildService(activeTransport = neverEndingTransport())

        assertTrue(service.start(DebridBenchmarkProvider.REAL_DEBRID))
        assertFalse(service.start(DebridBenchmarkProvider.PREMIUMIZE))
    }

    private fun buildService(
        activeTransport: DebridBenchmarkTransport
    ): DebridBenchmarkService {
        val resolver = mockk<DebridBenchmarkCandidateResolver>()
        val store = mockk<DebridBenchmarkStore>(relaxed = true)

        coEvery { resolver.resolve(any()) } answers {
            candidate(firstArg())
        }

        return DebridBenchmarkService(
            resolver = resolver,
            store = store,
            transport = activeTransport
        )
    }

    private fun neverEndingTransport(): DebridBenchmarkTransport {
        return object : DebridBenchmarkTransport {
            override suspend fun run(
                candidate: DebridBenchmarkCandidate,
                observer: DebridBenchmarkObserver
            ): DebridBenchmarkTransportResult {
                awaitCancellation()
            }
        }
    }

    private fun candidate(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = "https://example.com/${provider.storageKey}.mkv",
            headers = emptyMap(),
            filename = null,
            sourceSizeBytes = null
        )
    }
}
