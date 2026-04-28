package com.nexio.tv.ui.screens.cast

import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvdbPersonService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PersonDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CastDetailViewModelTest {

    @Test
    fun `fetchPersonDetail TMDB path routes through MetadataRouterFacade`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val tvdbPersonService = mockk<TvdbPersonService>()

        val fakePersonDetail = PersonDetail(
            tmdbId = 287,
            name = "Brad Pitt",
            biography = "American actor",
            birthday = "1963-12-18",
            deathday = null,
            placeOfBirth = "Springfield, Missouri",
            profilePhoto = "/path/to/profile.jpg",
            knownFor = "Actor",
            movieCredits = emptyList(),
            tvCredits = emptyList()
        )

        coEvery {
            facade.fetchPersonDetail(any(), any(), any())
        } returns fakePersonDetail

        val vm = CastDetailViewModel(
            metadataRouterFacade = facade,
            tvdbPersonService = tvdbPersonService,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "personId" to "287",
                    "personName" to "Brad%20Pitt",
                    "preferCrew" to false,
                    "provider" to "tmdb"
                )
            )
        )

        coVerify(atLeast = 1) {
            facade.fetchPersonDetail(
                metadataRequest = any(),
                personId = 287,
                preferCrewCredits = false
            )
        }
        coVerify(exactly = 0) { tvdbPersonService.fetchPersonDetail(any()) }
    }

    @Test
    fun `fetchPersonDetail TVDB path routes through MetadataRouterFacade with tvdb prefix`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val tvdbPersonService = mockk<TvdbPersonService>(relaxed = true)

        val fakePersonDetail = PersonDetail(
            tmdbId = 287,
            name = "Brad Pitt",
            biography = "American actor",
            birthday = "1963-12-18",
            deathday = null,
            placeOfBirth = "Springfield, Missouri",
            profilePhoto = "/path/to/profile.jpg",
            knownFor = "Actor",
            movieCredits = emptyList(),
            tvCredits = emptyList()
        )

        coEvery {
            facade.fetchPersonDetail(any(), any(), any())
        } returns fakePersonDetail

        val vm = CastDetailViewModel(
            metadataRouterFacade = facade,
            tvdbPersonService = tvdbPersonService,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "personId" to "287",
                    "personName" to "Brad%20Pitt",
                    "preferCrew" to false,
                    "provider" to "tvdb"
                )
            )
        )

        coVerify(atLeast = 1) {
            facade.fetchPersonDetail(
                metadataRequest = match { it.contentId == "tvdb:person:287" },
                personId = 287,
                preferCrewCredits = false
            )
        }
        coVerify(exactly = 0) { tvdbPersonService.fetchPersonDetail(any(), any()) }
        coVerify(exactly = 0) { tvdbPersonService.fetchPersonDetail(any()) }
    }
}
