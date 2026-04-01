package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService
import com.nexio.tv.domain.model.LibraryEntry
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
            realDebridItems = listOf(
                libraryEntry(provider = DebridBenchmarkProvider.REAL_DEBRID, listedAt = 1L, directPlaybackUrl = "a"),
                libraryEntry(provider = DebridBenchmarkProvider.REAL_DEBRID, listedAt = 2L, directPlaybackUrl = "b")
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
        realDebridItems: List<LibraryEntry> = emptyList(),
        premiumizeItems: List<LibraryEntry> = emptyList()
    ): DebridBenchmarkCandidateResolver {
        val debridLibraryService = mockk<DebridLibraryService>()

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)
        } returns realDebridItems
            .sortedByDescending { it.listedAt }
            .map { it.toBenchmarkCandidate(DebridBenchmarkProvider.REAL_DEBRID) }

        coEvery {
            debridLibraryService.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)
        } returns premiumizeItems
            .sortedByDescending { it.listedAt }
            .map { it.toBenchmarkCandidate(DebridBenchmarkProvider.PREMIUMIZE) }

        return DebridBenchmarkCandidateResolver(debridLibraryService)
    }

    private fun libraryEntry(
        provider: DebridBenchmarkProvider,
        listedAt: Long,
        directPlaybackUrl: String? = null
    ): LibraryEntry {
        return LibraryEntry(
            id = "${provider.name.lowercase()}:$listedAt",
            type = "movie",
            name = "Item $listedAt",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(provider.listKey),
            listedAt = listedAt,
            directPlaybackUrl = directPlaybackUrl
        )
    }

    private fun LibraryEntry.toBenchmarkCandidate(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate {
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = directPlaybackUrl.orEmpty(),
            headers = playbackHeaders.orEmpty(),
            filename = playbackFilename,
            sourceSizeBytes = null
        )
    }
}
