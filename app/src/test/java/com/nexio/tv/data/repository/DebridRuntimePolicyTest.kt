package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridRuntimePolicyTest {
    @Test
    fun `debrid providers remain serial until explicitly raised`() {
        val registry = defaultIntegrationPolicyRegistry()

        assertEquals(1, registry.policyFor(IntegrationProvider.REAL_DEBRID).maxConcurrentNetworkStarts)
        assertEquals(1, registry.policyFor(IntegrationProvider.PREMIUMIZE).maxConcurrentNetworkStarts)
        assertEquals(1, registry.policyFor(IntegrationProvider.TORBOX).maxConcurrentNetworkStarts)
        assertEquals(1, registry.policyFor(IntegrationProvider.EASY_DEBRID).maxConcurrentNetworkStarts)
    }
}
