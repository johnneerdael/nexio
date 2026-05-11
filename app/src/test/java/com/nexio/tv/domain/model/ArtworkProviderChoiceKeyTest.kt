package com.nexio.tv.domain.model

import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtworkProviderChoiceKeyTest {
    @Test fun `RPDB maps to RuntimeProvider(IntegrationProvider RPDB)`() {
        val result = ArtworkProviderChoiceKey.RPDB.toRuntimeProviderId()
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `TOP_POSTERS maps to RuntimeProvider(IntegrationProvider TOP_POSTERS)`() {
        val result = ArtworkProviderChoiceKey.TOP_POSTERS.toRuntimeProviderId()
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            result
        )
    }

    @Test fun `DEFAULT throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtworkProviderChoiceKey.DEFAULT.toRuntimeProviderId()
        }
    }
}
