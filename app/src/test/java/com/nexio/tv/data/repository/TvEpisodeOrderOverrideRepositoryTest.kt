package com.nexio.tv.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `observe orders emits after override changes`() = runTest {
        val repository = repository()
        val orders = repository.observeOrders()

        repository.setOrder("71446", TvEpisodeOrderProvider.TVDB_DEFAULT)

        assertEquals(
            mapOf("tmdb:tv:71446" to TvEpisodeOrderProvider.TVDB_DEFAULT),
            orders.first()
        )

        repository.clearOrder("71446")

        assertEquals(emptyMap<String, TvEpisodeOrderProvider>(), orders.first())
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

    @Test
    fun `public APIs reject invalid tmdb tv keys`() = runTest {
        val repository = repository()
        val invalidInputs = listOf("abc", "tmdb:movie:71446", " ")

        for (input in invalidInputs) {
            assertInvalidInput { repository.getOrder(input) }
            assertInvalidInput { repository.setOrder(input, TvEpisodeOrderProvider.TVDB_DEFAULT) }
            assertInvalidInput { repository.clearOrder(input) }
            assertInvalidInput { repository.hasOverride(input) }
        }
    }

    @Test
    fun `invalid persisted keys are ignored without dropping valid overrides`() = runTest {
        val file = overrideFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "overrides": {
                "abc": "TVDB_DEFAULT",
                "tmdb:movie:71446": "TVDB_DEFAULT",
                "tmdb:tv:71446": "TVDB_DEFAULT"
              }
            }
            """.trimIndent()
        )

        val repository = FileTvEpisodeOrderOverrideRepository(file)

        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, repository.getOrder("71446"))
    }

    private suspend fun assertInvalidInput(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun repository(): FileTvEpisodeOrderOverrideRepository =
        FileTvEpisodeOrderOverrideRepository(overrideFile())

    private fun overrideFile(): File {
        val dir = Files.createTempDirectory("tv-episode-order-overrides").toFile()
        return File(dir, "tv-episode-order-v1/episode-order-overrides-v1.json")
    }
}
