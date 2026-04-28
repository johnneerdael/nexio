package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider/scope-level backoff with exponential growth and jitter (F-D-06).
 *
 * Each consecutive failure doubles the block window starting from [baseMs] up to [capMs],
 * then adds a +/- [jitterMs] uniform jitter. A successful load is expected to call
 * [clear] to reset the schedule. An explicit `Retry-After` (when present) takes precedence
 * over the computed window.
 *
 * The Hilt-injected constructor uses [DEFAULT_BASE_MS] / [DEFAULT_CAP_MS] / [DEFAULT_JITTER_MS];
 * tests use the primary constructor directly to override these values.
 */
@Singleton
class IntegrationBackoffManager(
    private val dao: IntegrationProviderBackoffDao,
    private val baseMs: Long,
    private val capMs: Long,
    private val jitterMs: Long
) {
    @Inject
    constructor(dao: IntegrationProviderBackoffDao) : this(
        dao = dao,
        baseMs = DEFAULT_BASE_MS,
        capMs = DEFAULT_CAP_MS,
        jitterMs = DEFAULT_JITTER_MS
    )

    private val random = java.util.Random()

    suspend fun noteHttpFailure(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        statusCode: Int,
        retryAfterMs: Long?,
        reason: String?
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.get(provider.name, scope.storageKey)
        val nextFailures = (existing?.consecutiveFailures ?: 0) + 1
        val shift = (nextFailures - 1).coerceAtMost(20)
        val expBlockMs = (baseMs shl shift).coerceAtMost(capMs)
        val jitter = if (jitterMs > 0L) random.nextLong(jitterMs * 2L) - jitterMs else 0L
        val blockMs = retryAfterMs ?: (expBlockMs + jitter).coerceAtLeast(0L)
        dao.upsert(
            IntegrationProviderBackoffEntity(
                key = "${provider.name}:${scope.storageKey}",
                provider = provider.name,
                scopeKey = scope.storageKey,
                blockedUntilEpochMs = now + blockMs,
                statusCode = statusCode,
                reason = reason,
                updatedAtEpochMs = now,
                consecutiveFailures = nextFailures
            )
        )
    }

    suspend fun isBlocked(provider: IntegrationProvider, scope: IntegrationScope): Boolean {
        val entry = dao.get(provider.name, scope.storageKey) ?: return false
        return entry.blockedUntilEpochMs > System.currentTimeMillis()
    }

    suspend fun clear(provider: IntegrationProvider, scope: IntegrationScope) {
        dao.clear(provider.name, scope.storageKey)
    }

    companion object {
        const val DEFAULT_BASE_MS: Long = 2_000L
        const val DEFAULT_CAP_MS: Long = 60_000L
        const val DEFAULT_JITTER_MS: Long = 500L
    }
}
