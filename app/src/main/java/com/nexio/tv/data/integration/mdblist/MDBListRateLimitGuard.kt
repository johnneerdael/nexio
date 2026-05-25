package com.nexio.tv.data.integration.mdblist

import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val DAILY_LIMIT_MS = 24L * 60L * 60L * 1000L
private val MDBLIST_DAILY_SCOPE = IntegrationScope.ProviderConfig("mdblist:daily-api-limit")

class MDBListDailyLimitException : IOException("MDBList daily API limit backoff is active")

@Singleton
class MDBListRateLimitGuard @Inject constructor(
    private val backoffManager: IntegrationBackoffManager
) {
    suspend fun throwIfBlocked() {
        if (backoffManager.isBlocked(IntegrationProvider.MDBLIST, MDBLIST_DAILY_SCOPE)) {
            throw MDBListDailyLimitException()
        }
    }

    suspend fun isBlocked(): Boolean =
        backoffManager.isBlocked(IntegrationProvider.MDBLIST, MDBLIST_DAILY_SCOPE)

    suspend fun noteResponse(response: Response<*>): Long? {
        if (response.code() != 429) return null
        val retryAfterMs = response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.times(1000L)
        val errorText = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
        val isDailyLimit = errorText.contains("Daily API limit exceeded", ignoreCase = true)
        val blockMs = if (isDailyLimit) {
            maxOf(retryAfterMs ?: 0L, DAILY_LIMIT_MS)
        } else {
            retryAfterMs
        }
        backoffManager.noteHttpFailure(
            provider = IntegrationProvider.MDBLIST,
            scope = MDBLIST_DAILY_SCOPE,
            statusCode = 429,
            retryAfterMs = blockMs,
            reason = if (isDailyLimit) "mdblist_daily_api_limit" else "mdblist_http_429"
        )
        return blockMs
    }
}

internal fun noOpMDBListRateLimitGuard(): MDBListRateLimitGuard =
    MDBListRateLimitGuard(
        IntegrationBackoffManager(
            dao = object : IntegrationProviderBackoffDao {
                override suspend fun upsert(entity: IntegrationProviderBackoffEntity) = Unit
                override suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity? = null
                override suspend fun clear(provider: String, scopeKey: String) = Unit
            },
            baseMs = 0L,
            capMs = 0L,
            jitterMs = 0L
        )
    )
