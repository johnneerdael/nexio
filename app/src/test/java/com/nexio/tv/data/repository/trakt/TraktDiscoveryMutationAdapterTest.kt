package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.TraktRecommendationRef
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktDiscoveryMutationAdapterTest {
    @Test
    fun `dismiss recommendation envelope captures profile id`() {
        val envelope = TraktDiscoveryMutationAdapter.buildDismissRecommendationEnvelope(
            ref = recommendationRef(),
            profileId = 42
        )

        assertEquals(42, envelope.profileId)
    }

    @Test
    fun `execute hides recommendation with envelope profile session`() = runTest {
        val provider = mockk<TraktIntegrationProvider>()
        val settings = mockk<TraktSettingsDataStore>(relaxed = true)
        val adapter = TraktDiscoveryMutationAdapter(
            traktIntegrationProvider = provider,
            traktSettingsDataStore = settings
        )
        val envelope = TraktDiscoveryMutationAdapter.buildDismissRecommendationEnvelope(
            ref = recommendationRef(),
            profileId = 42
        )
        coEvery {
            provider.hideRecommendation(any(), any(), any())
        } returns Response.success(Unit)

        adapter.execute(envelope)

        coVerify(exactly = 1) {
            provider.hideRecommendation(
                TrackingAuthSession(TrackingProvider.TRAKT, 42),
                "movies",
                "fight-club-1999"
            )
        }
    }

    private fun recommendationRef(): TraktRecommendationRef =
        TraktRecommendationRef(
            recommendationKey = "movies:fight-club-1999",
            type = "movies",
            pathId = "fight-club-1999"
        )
}
