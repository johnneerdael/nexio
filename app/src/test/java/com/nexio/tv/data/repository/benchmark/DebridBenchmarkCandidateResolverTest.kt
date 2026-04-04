package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkCandidateResolverTest {

    @Test
    fun `resolver chooses one prepared candidate for a provider`() = runTest {
        val resolver = buildResolver(
            realDebridCandidates = DebridBenchmarkCandidateLookupResult.Candidates(
                listOf(
                    candidate(provider = DebridBenchmarkProvider.REAL_DEBRID, directUrl = "first"),
                    candidate(provider = DebridBenchmarkProvider.REAL_DEBRID, directUrl = "second")
                )
            ),
            chooseIndex = { size ->
                assertEquals(2, size)
                1
            }
        )

        val result = resolver.resolve(DebridBenchmarkProvider.REAL_DEBRID)

        assertTrue(result is DebridBenchmarkCandidateResolution.Candidate)
        assertEquals(
            "second",
            (result as DebridBenchmarkCandidateResolution.Candidate).value.directUrl
        )
    }

    @Test
    fun `resolver reports no large download when provider lookup has no large candidates`() = runTest {
        val resolver = buildResolver(
            premiumizeCandidates = DebridBenchmarkCandidateLookupResult.NoLargeDownload
        )

        assertEquals(
            DebridBenchmarkCandidateResolution.NoLargeDownload,
            resolver.resolve(DebridBenchmarkProvider.PREMIUMIZE)
        )
    }

    @Test
    fun `resolver reports no playable item when provider library is empty`() = runTest {
        val resolver = buildResolver()

        assertEquals(
            DebridBenchmarkCandidateResolution.NoPlayableLibraryItem,
            resolver.resolve(DebridBenchmarkProvider.PREMIUMIZE)
        )
    }

    @Test
    fun `resolver forwards torbox provider lookup`() = runTest {
        val resolver = buildResolver(
            torBoxCandidates = DebridBenchmarkCandidateLookupResult.Candidates(
                listOf(candidate(provider = DebridBenchmarkProvider.TORBOX, directUrl = "torbox"))
            )
        )

        val result = resolver.resolve(DebridBenchmarkProvider.TORBOX)

        assertTrue(result is DebridBenchmarkCandidateResolution.Candidate)
        assertEquals("torbox", (result as DebridBenchmarkCandidateResolution.Candidate).value.directUrl)
    }

    @Test
    fun `resolver forwards easydebrid provider lookup`() = runTest {
        val resolver = buildResolver(
            easyDebridCandidates = DebridBenchmarkCandidateLookupResult.Candidates(
                listOf(candidate(provider = DebridBenchmarkProvider.EASY_DEBRID, directUrl = "easy"))
            )
        )

        val result = resolver.resolve(DebridBenchmarkProvider.EASY_DEBRID)

        assertTrue(result is DebridBenchmarkCandidateResolution.Candidate)
        assertEquals("easy", (result as DebridBenchmarkCandidateResolution.Candidate).value.directUrl)
    }

    private fun buildResolver(
        realDebridCandidates: DebridBenchmarkCandidateLookupResult =
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem,
        premiumizeCandidates: DebridBenchmarkCandidateLookupResult =
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem,
        torBoxCandidates: DebridBenchmarkCandidateLookupResult =
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem,
        easyDebridCandidates: DebridBenchmarkCandidateLookupResult =
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem,
        chooseIndex: (Int) -> Int = { 0 }
    ): DebridBenchmarkCandidateResolver {
        val debridLibraryService = mockk<DebridLibraryService>()

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)
        } returns realDebridCandidates

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)
        } returns premiumizeCandidates

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.TORBOX)
        } returns torBoxCandidates

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.EASY_DEBRID)
        } returns easyDebridCandidates

        return DebridBenchmarkCandidateResolver(
            debridLibraryService = debridLibraryService,
            chooseIndex = chooseIndex
        )
    }

    private fun candidate(
        provider: DebridBenchmarkProvider,
        directUrl: String,
        sourceSizeBytes: Long? = null
    ): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = directUrl,
            headers = emptyMap(),
            filename = null,
            sourceSizeBytes = sourceSizeBytes
        )
    }
}
