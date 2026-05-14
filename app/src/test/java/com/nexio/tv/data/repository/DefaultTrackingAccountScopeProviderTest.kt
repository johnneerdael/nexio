package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultTrackingAccountScopeProviderTest {
    @Test
    fun `MDBList account scope uses configured API key hash without exposing raw key`() = runTest {
        val apiKey = "mdb_secret_key"
        val provider = DefaultTrackingAccountScopeProvider(
            traktAuthGateway = mockk(relaxed = true),
            simklAuthGateway = mockk(relaxed = true),
            mdbListSettingsReader = object : MDBListSettingsReader {
                override val settings = flowOf(MDBListSettings(enabled = true, apiKey = apiKey))
            }
        )

        val session = provider.accountScopedSession(TrackingProvider.MDBLIST, profileId = 7)

        assertEquals(TrackingProvider.MDBLIST, session.provider)
        assertEquals(7, session.profileId)
        assertEquals(credentialHash(IntegrationProvider.MDBLIST, apiKey), session.credentialHash)
    }
}
