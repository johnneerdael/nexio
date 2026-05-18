package com.nexio.tv.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvEpisodeOrderOverrideRepositoryTest {

    @Test
    fun `missing override defaults to tmdb`() = runTest {
        val repository = repository()

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, repository.getOrder("71446"))
        assertFalse(repository.hasOverride("71446"))
    }

    @Test
    fun `set and clear tvdb override`() = runTest {
        val repository = repository()

        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, repository.getOrder("71446"))
        assertTrue(repository.hasOverride("71446"))

        repository.clearOrder("71446")
        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, repository.getOrder("71446"))
        assertFalse(repository.hasOverride("71446"))
    }

    @Test
    fun `setting tmdb default clears stored override`() = runTest {
        val repository = repository()

        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)
        repository.setOrder("71446", TvEpisodeOrderProvider.TMDB_DEFAULT)

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, repository.getOrder("71446"))
        assertFalse(repository.hasOverride("71446"))
    }

    @Test
    fun `override survives repository recreation`() = runTest {
        val file = overrideFile()
        FileTvEpisodeOrderOverrideRepository(file)
            .setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)

        val reloaded = FileTvEpisodeOrderOverrideRepository(file)

        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, reloaded.getOrder("71446"))
        assertTrue(reloaded.hasOverride("71446"))
    }

    @Test
    fun `repository accepts normalized tmdb tv keys`() = runTest {
        val repository = repository()

        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)

        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, repository.getOrder("tmdb:71446"))
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, repository.getOrder("tmdb:tv:71446"))
        assertTrue(repository.hasOverride("tmdb:tv:71446"))
    }

    private fun repository(): FileTvEpisodeOrderOverrideRepository =
        FileTvEpisodeOrderOverrideRepository(overrideFile())

    private fun overrideFile(): File {
        val dir = Files.createTempDirectory("tv-episode-order-overrides").toFile()
        return File(dir, "tv-episode-order-v1/episode-order-overrides-v1.json")
    }
}
