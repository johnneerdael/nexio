package com.nexio.tv.core.tvdb

import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTrailerRecord
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbTrailerResolverLanguageFilterTest {

    @Test
    fun `rejects German trailer for English series and selects English candidate`() = runTest {
        val resolver = resolver(
            originalLanguage = "eng",
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/germanvideo1", language = "deu"),
                TvdbTrailerRecord(url = "https://youtu.be/englishvid1", language = "eng")
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals(
            "englishvid1",
            (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId
        )
    }

    @Test
    fun `rejects English trailer for German original series`() = runTest {
        val resolver = resolver(
            originalLanguage = "deu",
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/englishvid1", language = "eng"),
                TvdbTrailerRecord(url = "https://youtu.be/germanvideo1", language = "deu")
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Beispiel",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals(
            "germanvideo1",
            (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId
        )
    }

    @Test
    fun `rejects trailer with no declared language (strictest mode)`() = runTest {
        val resolver = resolver(
            originalLanguage = "eng",
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/unknownlang", language = null)
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertEquals(TvdbTrailerLookupResult.Missing, result)
    }

    @Test
    fun `falls back to English when series originalLanguage is missing`() = runTest {
        val resolver = resolver(
            originalLanguage = null,
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/germanvideo1", language = "deu"),
                TvdbTrailerRecord(url = "https://youtu.be/englishvid1", language = "eng")
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals(
            "englishvid1",
            (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId
        )
    }

    @Test
    fun `rejects German DirectMedia URL for English series (closes Gate C bypass)`() = runTest {
        val resolver = resolver(
            originalLanguage = "eng",
            trailers = listOf(
                TvdbTrailerRecord(
                    url = "https://cdn.example.com/trailer-de.mp4",
                    language = "deu"
                )
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertEquals(TvdbTrailerLookupResult.Missing, result)
    }

    @Test
    fun `accepts matching ISO 639-2 bibliographic code (ger maps to de)`() = runTest {
        val resolver = resolver(
            originalLanguage = "deu",
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/germanvideo1", language = "ger")
            )
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Beispiel",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals(
            "germanvideo1",
            (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId
        )
    }

    private fun resolver(
        originalLanguage: String?,
        trailers: List<TvdbTrailerRecord>
    ): TvdbTrailerResolver {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>()
        coEvery {
            identityService.resolveSeriesByTvdbId(100)
        } returns TvdbSeriesIdentity(tvdbId = 100)
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 100,
            originalLanguage = originalLanguage,
            trailers = trailers
        )
        return TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )
    }
}
