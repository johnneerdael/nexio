package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ProviderIds
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvEpisodeOrderResolverTest {

    @Test
    fun `resolver defaults to tmdb without override`() = runTest {
        val repository = repository()
        val resolver = DefaultTvEpisodeOrderResolver(repository)

        val resolution = resolver.resolve("71446", ProviderIds(tvdb = "81189"))

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, resolution.provider)
        assertEquals("tmdb:tv:71446", resolution.tmdbTvId)
        assertNull(resolution.tvdbSeriesId)
        assertEquals("tmdb default", resolution.reason)
    }

    @Test
    fun `resolver returns tvdb when override and tvdb sidecar exist`() = runTest {
        val repository = repository()
        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)
        val resolver = DefaultTvEpisodeOrderResolver(repository)

        val resolution = resolver.resolve("tmdb:71446", ProviderIds(tvdb = "81189"))

        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, resolution.provider)
        assertEquals("tmdb:tv:71446", resolution.tmdbTvId)
        assertEquals("81189", resolution.tvdbSeriesId)
        assertEquals("tvdb override", resolution.reason)
    }

    @Test
    fun `resolver falls back to tmdb for request when tvdb sidecar is missing`() = runTest {
        val repository = repository()
        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)
        val resolver = DefaultTvEpisodeOrderResolver(repository)

        val resolution = resolver.resolve("71446", ProviderIds(tmdb = "71446"))

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, resolution.provider)
        assertEquals("tmdb:tv:71446", resolution.tmdbTvId)
        assertNull(resolution.tvdbSeriesId)
        assertEquals("tvdb override missing tvdb sidecar", resolution.reason)
        assertTrue(repository.hasOverride("71446"))
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, repository.getOrder("71446"))
    }

    @Test
    fun `resolver handles blank tmdb id without throwing`() = runTest {
        val resolver = DefaultTvEpisodeOrderResolver(repository())

        val resolution = resolver.resolve(" ", ProviderIds(tvdb = "81189"))

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, resolution.provider)
        assertEquals("", resolution.tmdbTvId)
        assertNull(resolution.tvdbSeriesId)
        assertEquals("missing tmdb tv id", resolution.reason)
    }

    private fun repository(): FileTvEpisodeOrderOverrideRepository =
        FileTvEpisodeOrderOverrideRepository(overrideFile())

    private fun overrideFile(): File {
        val dir = Files.createTempDirectory("tv-episode-order-resolver").toFile()
        return File(dir, "tv-episode-order-v1/episode-order-overrides-v1.json")
    }
}
