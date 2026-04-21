package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationBackoffManager @Inject constructor(
    private val dao: IntegrationProviderBackoffDao
) {
    suspend fun noteHttpFailure(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        statusCode: Int,
        retryAfterMs: Long?,
        reason: String?
    ) {
        val blockMs = retryAfterMs ?: if (statusCode == 429) 2_000L else 5_000L
        dao.upsert(
            IntegrationProviderBackoffEntity(
                key = "${provider.name}:${scope.storageKey}",
                provider = provider.name,
                scopeKey = scope.storageKey,
                blockedUntilEpochMs = System.currentTimeMillis() + blockMs,
                statusCode = statusCode,
                reason = reason,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun isBlocked(provider: IntegrationProvider, scope: IntegrationScope): Boolean {
        val entry = dao.get(provider.name, scope.storageKey) ?: return false
        return entry.blockedUntilEpochMs > System.currentTimeMillis()
    }
}
