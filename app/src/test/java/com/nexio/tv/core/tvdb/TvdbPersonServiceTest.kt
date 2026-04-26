package com.nexio.tv.core.tvdb

import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbBiography
import com.nexio.tv.data.remote.api.TvdbCharacterRecord
import com.nexio.tv.data.remote.api.TvdbLinkedEntity
import com.nexio.tv.data.remote.api.TvdbPersonExtendedRecord
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbPersonServiceTest {

    private val provider = mockk<TvdbIntegrationProvider>()
    private val service = TvdbPersonService(provider = provider)

    @Test
    fun `non-positive people id returns null without fetching`() = runTest {
        val result = service.fetchPersonDetail(0)

        assertNull(result)
        coVerify(exactly = 0) { provider.fetchPersonExtended(any()) }
    }

    @Test
    fun `person detail uses injected provider and maps fields`() = runTest {
        coEvery { provider.fetchPersonExtended(101) } returns TvdbPersonExtendedRecord(
            id = 101,
            name = "  Jane Doe  ",
            image = " https://images.example/jane.jpg ",
            biographies = listOf(
                TvdbBiography(biography = "Fallback biography", language = "fra"),
                TvdbBiography(biography = "Preferred biography", language = "eng"),
                TvdbBiography(biography = " ", language = "eng")
            ),
            birth = " 1980-01-02 ",
            death = null,
            birthPlace = " Gotham ",
            characters = listOf(
                TvdbCharacterRecord(
                    seriesId = 10,
                    series = TvdbLinkedEntity(
                        image = "https://images.example/series.jpg",
                        name = "  Space Story  ",
                        year = "2001"
                    ),
                    movieId = 20,
                    movie = TvdbLinkedEntity(
                        image = "https://images.example/movie.jpg",
                        name = "  Movie Night  ",
                        year = "2003"
                    ),
                    name = "  Lead  "
                )
            )
        )

        val result = service.fetchPersonDetail(101)

        assertEquals(0, result?.tmdbId)
        assertNotEquals(101, result?.tmdbId)
        assertEquals("Jane Doe", result?.name)
        assertEquals("Preferred biography", result?.biography)
        assertEquals("1980-01-02", result?.birthday)
        assertNull(result?.deathday)
        assertEquals("Gotham", result?.placeOfBirth)
        assertEquals("https://images.example/jane.jpg", result?.profilePhoto)
        assertEquals(listOf("tvdb:10"), result?.tvCredits?.map { it.id })
        assertEquals(listOf("tvdb-movie:20"), result?.movieCredits?.map { it.id })
        assertEquals(ContentType.SERIES, result?.tvCredits?.firstOrNull()?.type)
        assertEquals(ContentType.MOVIE, result?.movieCredits?.firstOrNull()?.type)
        coVerify(exactly = 1) { provider.fetchPersonExtended(101) }
    }
}
