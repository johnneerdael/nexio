package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridBenchmarkCandidateResolverTest {

    @Test
    fun `resolver picks the newest playable real debrid item with a direct playback url`() = runTest {
        val resolver = buildResolver(
            realDebridCandidates = listOf(
                candidate(provider = DebridBenchmarkProvider.REAL_DEBRID, directUrl = "b"),
                candidate(provider = DebridBenchmarkProvider.REAL_DEBRID, directUrl = "a")
            )
        )

        assertEquals("b", resolver.resolve(DebridBenchmarkProvider.REAL_DEBRID)?.directUrl)
    }

    @Test
    fun `resolver reports no candidate when provider library is empty`() = runTest {
        val resolver = buildResolver()

        assertNull(resolver.resolve(DebridBenchmarkProvider.PREMIUMIZE))
    }

    private fun buildResolver(
        realDebridCandidates: List<DebridBenchmarkCandidate> = emptyList(),
        premiumizeCandidates: List<DebridBenchmarkCandidate> = emptyList()
    ): DebridBenchmarkCandidateResolver {
        val debridLibraryService = mockk<DebridLibraryService>()

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)
        } returns realDebridCandidates

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)
        } returns premiumizeCandidates

        return DebridBenchmarkCandidateResolver(debridLibraryService)
    }

    private fun candidate(
        provider: DebridBenchmarkProvider,
        directUrl: String
    ): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = directUrl,
            headers = emptyMap(),
            filename = null,
            sourceSizeBytes = null
        )
    }
}
