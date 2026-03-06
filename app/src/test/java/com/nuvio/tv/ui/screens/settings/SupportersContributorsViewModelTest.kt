package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.remote.api.DonationsApi
import com.nuvio.tv.data.remote.dto.DonationDto
import com.nuvio.tv.data.remote.dto.DonationsResponseDto
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.data.repository.GitHubContributorsRepository
import com.nuvio.tv.data.repository.SupportersRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SupportersContributorsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial state defaults to supporters`() {
        val viewModel = SupportersContributorsViewModel(
            supportersRepository = SupportersRepository(FakeDonationsApi()),
            contributorsRepository = GitHubContributorsRepository(FakeGitHubApi())
        )

        assertEquals(SupportersContributorsTab.Supporters, viewModel.uiState.value.selectedTab)
        assertFalse(viewModel.uiState.value.hasLoadedContributors)
    }

    @Test
    fun `selecting contributors loads once and keeps loaded state`() = runTest {
        val api = FakeGitHubApi()
        val viewModel = SupportersContributorsViewModel(
            supportersRepository = SupportersRepository(FakeDonationsApi()),
            contributorsRepository = GitHubContributorsRepository(api)
        )

        viewModel.onSelectTab(SupportersContributorsTab.Contributors)
        advanceUntilIdle()
        viewModel.onSelectTab(SupportersContributorsTab.Supporters)
        viewModel.onSelectTab(SupportersContributorsTab.Contributors)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, api.contributorRequests)
        assertTrue(state.hasLoadedContributors)
        assertEquals(1, state.contributors.size)
        assertNull(state.contributorsErrorMessage)
    }

    @Test
    fun `selecting and dismissing contributor details updates state`() = runTest {
        val api = FakeGitHubApi()
        val viewModel = SupportersContributorsViewModel(
            supportersRepository = SupportersRepository(FakeDonationsApi()),
            contributorsRepository = GitHubContributorsRepository(api)
        )

        viewModel.onSelectTab(SupportersContributorsTab.Contributors)
        advanceUntilIdle()

        val contributor = viewModel.uiState.value.contributors.first()
        viewModel.onContributorSelected(contributor)
        assertEquals(contributor, viewModel.uiState.value.selectedContributor)

        viewModel.dismissContributorDetails()
        assertNull(viewModel.uiState.value.selectedContributor)
    }

    @Test
    fun `loads supporters on init and opens supporter details`() = runTest {
        val donationsApi = FakeDonationsApi()
        val viewModel = SupportersContributorsViewModel(
            supportersRepository = SupportersRepository(donationsApi),
            contributorsRepository = GitHubContributorsRepository(FakeGitHubApi())
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, donationsApi.requests)
        assertTrue(state.hasLoadedSupporters)
        assertEquals(1, state.supporters.size)

        val supporter = state.supporters.first()
        viewModel.onSupporterSelected(supporter)
        assertEquals(supporter, viewModel.uiState.value.selectedSupporter)

        viewModel.dismissSupporterDetails()
        assertNull(viewModel.uiState.value.selectedSupporter)
    }

    private class FakeGitHubApi : GitHubReleaseApi {
        var contributorRequests: Int = 0

        override suspend fun getLatestRelease(
            owner: String,
            repo: String
        ): Response<GitHubReleaseDto> {
            error("Not needed for this test")
        }

        override suspend fun getContributors(
            owner: String,
            repo: String
        ): Response<List<GitHubContributorDto>> {
            contributorRequests += 1
            return Response.success(
                listOf(
                    GitHubContributorDto(
                        login = "alice",
                        avatarUrl = "https://example.com/alice.png",
                        htmlUrl = "https://github.com/alice",
                        contributions = if (repo == "NuvioTV") 3 else 5,
                        type = "User"
                    )
                )
            )
        }
    }

    private class FakeDonationsApi : DonationsApi {
        var requests: Int = 0

        override suspend fun getDonations(limit: Int): Response<DonationsResponseDto> {
            requests += 1
            return Response.success(
                DonationsResponseDto(
                    donations = listOf(
                        DonationDto(
                            name = "Alice",
                            amount = 15.0,
                            currency = "USD",
                            date = "2025-01-01T00:00:00Z",
                            message = "Keep going"
                        )
                    )
                )
            )
        }
    }
}
