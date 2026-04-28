package com.nexio.tv.ui.screens.cast

import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.tvdb.TvdbPersonService
import com.nexio.tv.domain.model.PersonDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * F-05-04 closeout: pins the TVDB branch of [CastDetailViewModel.loadPersonDetail] onto
 * [MetadataRouterFacade.fetchPersonDetail] (which dispatches TvdbOrganizationPersonAdapter
 * via the canonical resolver pipeline) instead of bypassing the router with a direct
 * [TvdbPersonService.fetchPersonDetail] call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastDetailViewModelTvdbMigrationTest {

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tvdb provider routes through MetadataRouterFacade fetchPersonDetail with tvdb prefix`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val tvdbService = mockk<TvdbPersonService>(relaxed = true)
        coEvery {
            facade.fetchPersonDetail(
                metadataRequest = match { it.contentId == "tvdb:person:287" },
                personId = 287,
                preferCrewCredits = any()
            )
        } returns samplePersonDetail()

        CastDetailViewModel(
            metadataRouterFacade = facade,
            tvdbPersonService = tvdbService,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "personId" to "287",
                    "personName" to "Test%20Person",
                    "preferCrew" to false,
                    "provider" to "tvdb"
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            facade.fetchPersonDetail(
                metadataRequest = match { it.contentId == "tvdb:person:287" },
                personId = 287,
                preferCrewCredits = false
            )
        }
        coVerify(exactly = 0) { tvdbService.fetchPersonDetail(any(), any()) }
        coVerify(exactly = 0) { tvdbService.fetchPersonDetail(any()) }
    }

    private fun samplePersonDetail(): PersonDetail = PersonDetail(
        tmdbId = 287,
        name = "Test Person",
        biography = "bio",
        birthday = null,
        deathday = null,
        placeOfBirth = null,
        profilePhoto = null,
        knownFor = null,
        movieCredits = emptyList(),
        tvCredits = emptyList()
    )
}
